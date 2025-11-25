package ret.tawny.truthful.checks.impl.movement.speed;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.world.WorldUtils;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'A', type = CheckType.SPEED)
public final class SpeedA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        if (!relMovePacketWrapper.isPositionUpdate()) return;

        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null) return;

        // Exemptions
        if (player.getAllowFlight() || player.isFlying() || player.isGliding() || player.isInsideVehicle()) return;
        if (data.isTeleportTick()) return;

        // FIX: Reduced Vehicle Exit Immunity (40 -> 10)
        if (data.getTicksTracked() - data.getLastVehicleExitTick() < 10) {
            buffer.decrease(player, 0.5);
            return;
        }

        // Web Exemption
        if (data.isInWeb() || (data.getTicksTracked() - data.getLastWebTick() < 20)) {
            buffer.decrease(player, 0.5);
            return;
        }

        if (data.getMaxVelocity() > 0) {
            buffer.decrease(player, 1.0);
            return;
        }

        try { if (player.isRiptiding()) return; } catch (Throwable ignored) {}

        if (data.getTicksTracked() - data.getLastGlideTick() < 20) {
            buffer.decrease(player, 0.5);
            return;
        }

        double limit = 0.0;
        double attributeSpeed = getBaseSpeed(player);

        if (data.isOnGround()) {
            float friction = WorldUtils.getSlippinessMultiplier(player);
            limit = attributeSpeed;

            // Ice/Slime adjustment
            if (friction > 0.65) {
                limit *= 2.3;
            } else {
                limit += 0.1;
            }
        } else {
            // Air Speed Limit
            limit = 0.36 + (getPotionLevel(player, PotionEffectType.SPEED) * 0.05);

            // Jump Boost increases air speed slightly due to momentum
            if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                limit += 0.05;
            }

            if (WorldUtils.hasLowFrictionBelow(player)) {
                limit = 0.65 + (getPotionLevel(player, PotionEffectType.SPEED) * 0.05);
            }
        }

        limit += 0.04; // Buffer

        double speed = data.getDeltaXZ();

        if (speed > limit) {
            if (buffer.increase(player, 1.0) > 10.0) {
                flag(data, String.format("Speed Limit Exceeded. Speed: %.3f, Max: %.3f", speed, limit));
            }
        } else {
            buffer.decrease(player, 0.5);
        }
    }

    private double getBaseSpeed(Player player) {
        double base = 0.285;
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            base *= 1.0 + 0.2 * getPotionLevel(player, PotionEffectType.SPEED);
        }
        if (player.hasPotionEffect(PotionEffectType.SLOWNESS)) {
            base = base * (1.0 - 0.15 * getPotionLevel(player, PotionEffectType.SLOWNESS));
        }
        return base;
    }

    private int getPotionLevel(Player player, PotionEffectType type) {
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType().equals(type)) return effect.getAmplifier() + 1;
        }
        return 0;
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}