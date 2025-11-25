package ret.tawny.truthful.checks.impl.movement.strafe;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.WorldUtils;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'A', type = CheckType.STRAFE)
@SuppressWarnings("unused")
public final class StrafeA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(8.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper event) {
        final Player player = event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null) return;

        // Exemption: Flying/Creative/Spectator
        if (player.isFlying() || data.isExempt()) return;

        // Exemption: Under Block (Head Hitter)
        if (data.isUnderBlock()) {
            buffer.decrease(player, 0.25);
            return;
        }

        // 1. Ice/Slime Exemption (Low friction allows high speed)
        if (WorldUtils.hasLowFrictionBelow(player)) return;

        // 2. Landing/Momentum Exemption (CRITICAL FIX)
        // When landing, you carry air speed (~0.35 - 0.6) for a few ticks.
        // We only want to check SUSTAINED ground speed here.
        // Let SpeedB handle the complex momentum math.
        if (data.getTicksOnGround() < 7) {
            buffer.decrease(player, 0.1);
            return;
        }

        // 3. Stair/Slab Exemption
        // Walking down stairs increases horizontal speed momentarily.
        // If we are moving vertically while on "ground", we are likely on stairs/slopes.
        if (Math.abs(data.getDeltaY()) > 0.001) {
            return;
        }

        double deltaX = data.getDeltaX();
        double deltaZ = data.getDeltaZ();
        double speed = Math.hypot(deltaX, deltaZ);

        // Calculate base speed with attributes
        double maxSpeed = getBaseSpeed(player);

        // Buffer for turning/strafing variants
        // 0.03 covers 45-degree strafe optimization
        maxSpeed += 0.03;

        // Logic: Ground Speed Limit
        if (speed > maxSpeed && data.isOnGround()) {

            // Check if velocity is active (velocity would explain high speed)
            if (data.getVelocities().isEmpty()) {
                if (buffer.increase(player, 1.0) > 8.0) {
                    flag(data, String.format("Invalid Strafe Speed: %.3f > %.3f", speed, maxSpeed));
                    buffer.reset(player, 4.0);
                }
            }
        } else {
            buffer.decrease(player, 0.1);
        }
    }

    private double getBaseSpeed(Player player) {
        // Vanilla Base Speeds: ~0.22 (Walk), ~0.281 (Sprint)
        double base = 0.29;
        if (player.isSprinting())
            base = 0.295;

        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SPEED)) {
            int amplifier = getPotionLevel(player, org.bukkit.potion.PotionEffectType.SPEED);
            base *= 1.0 + 0.2 * amplifier;
        }
        if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS)) {
            int amplifier = getPotionLevel(player, org.bukkit.potion.PotionEffectType.SLOWNESS);
            base = base * (1.0 - 0.15 * amplifier);
        }
        return base;
    }

    private int getPotionLevel(Player player, org.bukkit.potion.PotionEffectType type) {
        for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType().equals(type))
                return effect.getAmplifier() + 1;
        }
        return 0;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}