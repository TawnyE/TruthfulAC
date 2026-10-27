package ret.tawny.truthful.checks.impl.combat.killaura;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.ExemptionType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.data.world.CompensatedWorld;

@CheckData(order = 'F', type = CheckType.KILLAURA)
public final class KillAuraF extends Check {

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
        if (data == null)
            return;

        if (data.isExempt(ExemptionType.LIQUID) || data.isExempt(ExemptionType.CLIMBABLE) ||
                data.isExempt(ExemptionType.WEB) || data.isNearVehicle() || data.isMovementExempt() ||
                data.hasVelocity() || data.isUnderBlock()) {
            return;
        }

        // Stepping up a slab/stair creates a small Y movement that isn't a jump
        if (isNearStairOrSlab(data)) {
            return;
        }

        // Logic: Packet Criticals
        // Critical hits occur when fall distance > 0.0.
        // Cheat clients send tiny hops (0.0625) to trick the server into thinking they
        // are falling.

        boolean clientGround = data.isClientGround();
        double realDeltaY = data.getDeltaY();

        // Case 1: Client claims Air, but hasn't moved vertically enough to be jumping
        if (!clientGround && !data.isServerGround()) {

            // Valid jump is usually ~0.42.
            // Packet crits are often < 0.1 or even 0.0.

            if (data.getAirTicks() > 3) {
                buffer.decrease(player, 0.1);
                return;
            }

            if (realDeltaY > 0 && realDeltaY < 0.2) {
                // If we are here, we are NOT under a block (checked above) and NOT taking
                // velocity.
                // This means the small hop is suspicious.

                if (buffer.increase(player, 1.5) > 6.0) {
                    flag(data, String.format("Packet Criticals (Mini-Jump). Y: %.4f, AirTicks: %d", realDeltaY,
                            data.getAirTicks()));
                    buffer.reset(player, 3.0);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }

    private boolean isNearStairOrSlab(PlayerData data) {
        CompensatedWorld world = data.getWorldCache();
        int px = (int) Math.floor(data.getX());
        int py = (int) Math.floor(data.getY());
        int pz = (int) Math.floor(data.getZ());

        // Check feet and block below
        for (int y = py - 1; y <= py; y++) {
            for (int x = px - 1; x <= px + 1; x++) {
                for (int z = pz - 1; z <= pz + 1; z++) {
                    WrappedBlockState state = world.getBlockState(x, y, z);
                    String name = state.getType().getName().toUpperCase();
                    // Anything you step UP onto with a sub-0.2 deltaY flips ground
                    // state on its own: stairs/slabs plus the thin-block family
                    // (snow layers, carpet, path, farmland, beds...).
                    if (name.contains("STAIR") || name.contains("SLAB") || name.contains("STEP")
                            || name.contains("SNOW") || name.contains("CARPET") || name.contains("BED")
                            || name.contains("CHEST") || name.contains("DIRT_PATH") || name.contains("FARMLAND")
                            || name.contains("DAYLIGHT") || name.contains("REPEATER") || name.contains("COMPARATOR")
                            || name.contains("TRAPDOOR") || name.contains("SCAFFOLDING") || name.contains("CAKE")
                            || name.contains("SNOW_LAYER") || name.contains("ENCHANTING_TABLE")
                            || name.contains("POTION_STAND") || name.contains("BREWING_STAND")
                            || name.contains("HEAD") || name.contains("SKULL") || name.contains("LANTERN")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}