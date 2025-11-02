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
import ret.tawny.truthful.utils.world.WorldUtils;
import ret.tawny.truthful.version.IVersionAdapter;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

@CheckData(order = 'A', type = CheckType.SPEED)
public final class SpeedA extends Check {

    private final CheckBuffer buffer = new CheckBuffer(4.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper relMovePacketWrapper) {
        if (!relMovePacketWrapper.isPositionUpdate()) return;

        final Player player = relMovePacketWrapper.getPlayer();
        final PlayerData playerData = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (playerData == null) return;

        if (player.getAllowFlight() || player.isFlying() || player.isGliding() || player.isInsideVehicle()) return;
        if (playerData.isTeleportTick() || !playerData.getVelocities().isEmpty()) return;
        if (playerData.isInLiquid() || playerData.wasInLiquid() || playerData.getTicksSinceAbility() < 3) return;

        final IVersionAdapter adapter = Truthful.getInstance().getVersionManager().getAdapter();
        double max = playerData.isOnGround() ? adapter.getBaseGroundSpeed(player) : adapter.getBaseAirSpeed(player);

        if (WorldUtils.hasLowFrictionBelow(player)) {
            max += 0.3D;
        }

        // Add a small tolerance for network fluctuations
        max += 0.03;

        final double horizontal = playerData.getDeltaXZ();
        final double excess = horizontal - max;

        if (excess > 0) {
            if (buffer.increase(player, excess * 5) > 4.0) {
                flag(playerData, String.format("Speed %.3f > %.3f", horizontal, max));
                buffer.reset(player, 2.0);
            }
        } else {
            buffer.decrease(player, 0.75);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}