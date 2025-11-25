package ret.tawny.truthful.checks.impl.movement.speed;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.utils.player.PredictionUtils;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'B', type = CheckType.SPEED)
@SuppressWarnings("unused")
public final class SpeedB extends Check {

    private final CheckBuffer buffer = new CheckBuffer(15.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        if (!relMovePacketWrapper.isPositionUpdate()) return;

        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null) return;
        if (player.getAllowFlight() || player.isFlying() || player.isGliding() || player.isInsideVehicle()) return;
        if (data.isTeleportTick() || !data.getVelocities().isEmpty()) return;
        if (data.isInLiquid() || data.wasInLiquid()) return;

        // FIX: Reduced Vehicle Exit Immunity (40 -> 10)
        if (data.getTicksTracked() - data.getLastVehicleExitTick() < 10) {
            buffer.decrease(player, 0.5);
            return;
        }

        double deltaXZ = data.getDeltaXZ();
        double lastDeltaXZ = data.getLastDeltaXZ();

        float friction = 0.91F;
        if (data.isLastGround()) {
            friction *= PredictionUtils.getBlockFriction(player.getWorld(), data.getLastLocation());
        }

        double acceleration;
        if (data.isLastGround()) {
            acceleration = PredictionUtils.getBaseSpeed(player) * (0.16277136 / (friction * friction * friction));
            if (data.getDeltaY() > 0.0) acceleration += 0.2;
        } else {
            acceleration = 0.026;
            if (player.hasPotionEffect(org.bukkit.potion.PotionEffectType.SPEED)) acceleration += 0.005;
        }

        double predicted = (lastDeltaXZ * friction) + acceleration;
        double bufferLimit = predicted + 0.05;

        if (deltaXZ > bufferLimit) {
            if (deltaXZ > 0.2) {
                double diff = deltaXZ - predicted;
                if (buffer.increase(player, 1.0) > 15.0) {
                    flag(data, String.format("Friction. Diff: %.4f, Speed: %.3f", diff, deltaXZ));
                    buffer.reset(player, 10.0);
                }
            }
        } else {
            buffer.decrease(player, 0.25);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}