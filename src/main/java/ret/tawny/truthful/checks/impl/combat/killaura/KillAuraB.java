package ret.tawny.truthful.checks.impl.combat.killaura;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.ExemptionType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.data.world.CompensatedWorld;

/**
 * KillAuraB: Movement Correlation (KeepSprint)
 * Fixed: Added Velocity check to prevent false flags when taking KB.
 * Thread-Safe: Uses CompensatedWorld and math for direction.
 */
@CheckData(order = 'B', type = CheckType.KILLAURA)
public final class KillAuraB extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY)
            return;
        WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
        if (interact.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK)
            return;

        final Player player = (Player) event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isExempt())
            return;

        // === 1. ANGLE CHECK (Omni-Sprint in Combat) ===
        // Strafing (W+A/W+D) legitimately swings the movement vector up to
        // ~45 degrees off the look while sprinting; rapid A/D mashing makes
        // it swing further transiently via momentum. Only clearly BACKWARD
        // sprint (>~70 degrees off look) with real speed is impossible.
        if (data.isSprinting() && data.getDeltaXZ() > 0.22) {

            if (data.hasVelocity() || data.isExempt(ExemptionType.VELOCITY)) {
                buffer.decrease(player, 0.5);
                return;
            }

            Vector move = new Vector(data.getDeltaX(), 0, data.getDeltaZ()).normalize();
            Vector look = getDirection(data.getYaw(), 0).normalize(); // Y=0 for 2D dot product

            double dot = move.dot(look);

            // 0.3 = ~72 degrees: backwards or near-sideways sprint at speed.
            if (dot < 0.3) {
                if (buffer.increase(player, 1.0) > 6.0) {
                    flag(data, String.format("Directional Sprint. Dot: %.2f", dot));
                    buffer.reset(player, 3.0);
                }
                return;
            }
        }

        // === 2. KEEP SPRINT CHECK ===
        int ticksSinceLastHit = data.getTicksTracked() - data.getLastHitTick();

        if (ticksSinceLastHit < 3 && ticksSinceLastHit >= 1) {
            if (data.isOnGround() && data.isSprinting()) {
                // If taking velocity, speed might be weird
                if (data.hasVelocity())
                    return;

                // Landing ticks carry sprint-jump momentum: the attack slow
                // hasn't fully applied yet - exempt the first ticks back on
                // the ground (PvP jump-attacking is legit).
                if (data.getAirTicks() == 0 && data.getPositionTracker().getLastAirTicks() > 2)
                    return;

                if (data.getDeltaXZ() > 0.30) {
                    if (isNearIce(data))
                        return;
                    if (data.isExempt(ExemptionType.VELOCITY))
                        return;
                    // Speed potions push legit sprint speed past this floor
                    // (Speed II sprinting is ~0.39/tick) - without this the
                    // check flagged ordinary potion PvP.
                    if (data.getPotionLevel(org.bukkit.potion.PotionEffectType.SPEED) > 0)
                        return;

                    if (buffer.increase(player, 1.5) > 8.0) {
                        flag(data, String.format("KeepSprint. Speed: %.4f", data.getDeltaXZ()));
                        buffer.reset(player, 4.0);
                    }
                } else {
                    buffer.decrease(player, 0.2);
                }
            }
        } else {
            buffer.decrease(player, 0.1);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }

    private Vector getDirection(float yaw, float pitch) {
        Vector vector = new Vector();
        double rotX = (double) yaw;
        double rotY = (double) pitch;
        vector.setY(-Math.sin(Math.toRadians(rotY)));
        double xz = Math.cos(Math.toRadians(rotY));
        vector.setX(-xz * Math.sin(Math.toRadians(rotX)));
        vector.setZ(xz * Math.cos(Math.toRadians(rotX)));
        return vector;
    }

    private boolean isNearIce(PlayerData data) {
        CompensatedWorld world = data.getWorldCache();
        int x = (int) Math.floor(data.getX());
        int y = (int) Math.floor(data.getY() - 0.5);
        int z = (int) Math.floor(data.getZ());

        // Check 3x3 footprint below
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                WrappedBlockState state = world.getBlockState(x + dx, y, z + dz);
                if (state.getType().getName().toUpperCase().contains("ICE")) {
                    return true;
                }
            }
        }
        return false;
    }
}