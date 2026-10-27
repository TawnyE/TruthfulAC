package ret.tawny.truthful.checks.impl.movement.spoof;

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

/**
 * GroundSpoofD - Void Ground Validation
 *
 * Prevents players from claiming to be on ground while below the world floor (Y < -64).
 */
@CheckData(order = 'D', type = CheckType.SPOOF)
public final class GroundSpoofD extends Check {

    private final CheckBuffer buffer = new CheckBuffer(5.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        if (!wrapper.isPositionUpdate()) return;
        if (!wrapper.isGround()) return;

        final Player player = wrapper.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        double y = wrapper.getY();
        double minHeight = data.getWorldMinHeight();

        // Void Check
        if (y < minHeight - 1.0) {
            if (buffer.increase(player, 2.0) > 2.0) {
                flag(data, String.format("Void Ground Claim. Y: %.2f", y));
                buffer.reset(player, 5.0);
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}