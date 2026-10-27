package ret.tawny.truthful.checks.impl.packet.badpacket;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.wrapper.impl.client.position.RelMovePacketWrapper;

/**
 * BadPacketF - non-finite rotations. NaN or infinite yaw/pitch. Runs on the
 * movement path because rotation packets live there, not with the other
 * packet checks. Y owns the coordinates, F owns the angles.
 */
@CheckData(order = 'F', type = CheckType.BAD_PACKET)
public final class BadPacketF extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        if (!wrapper.isRotationUpdate()) return;
        final PlayerData data = wrapper.getPlayerData();
        if (data == null) return;
        final Player player = data.getPlayer();
        if (player == null) return;

        if (!Float.isFinite(wrapper.getYaw()) || !Float.isFinite(wrapper.getPitch())) {
            if (buffer.increase(player, 3.0) > 2.0) {
                flag(data, "Non-finite rotation");
                buffer.reset(player, 1.0);
            }
        } else {
            buffer.decrease(player, 0.5);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
    }
}
