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
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'C', type = CheckType.SPEED)
@SuppressWarnings("unused")
public final class SpeedC extends Check {

    private final CheckBuffer buffer = new CheckBuffer(20.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        if (!relMovePacketWrapper.isPositionUpdate()) return;

        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null || data.isOnGround() || data.isTeleportTick() || data.getTicksInAir() < 2) return;
        if (player.getAllowFlight() || player.isGliding() || data.isInLiquid() || data.isOnClimbable()) return;

        double deltaXZ = data.getDeltaXZ();
        double lastDeltaXZ = data.getLastDeltaXZ();

        double acceleration = deltaXZ - lastDeltaXZ;
        double limit = 0.02; // A conservative value for air acceleration

        if (acceleration > limit) {
            if (buffer.increase(player, 1.0) > 5.0) {
                flag(data, String.format("Exceeded air acceleration limit. A: %.5f, L: %.5f", acceleration, limit));
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
