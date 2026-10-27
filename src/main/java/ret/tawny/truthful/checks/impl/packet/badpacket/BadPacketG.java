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
 * BadPacketG - positions outside the world. Way below the void or above the
 * build limit with margin. Runs on the movement path, same as F and Y.
 */
@CheckData(order = 'G', type = CheckType.BAD_PACKET)
public final class BadPacketG extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        if (!wrapper.isPositionUpdate()) return;
        final PlayerData data = wrapper.getPlayerData();
        if (data == null) return;
        final Player player = data.getPlayer();
        if (player == null) return;

        double x = wrapper.getX();
        double y = wrapper.getY();
        double z = wrapper.getZ();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return; // Y's job

        int minY = -64;
        int maxY = 320;
        try {
            minY = data.getWorldMinHeight();
            maxY = data.getWorldMaxHeight();
        } catch (Throwable ignored) {}

        if (y < minY - 64.0D || y > maxY + 64.0D
                || Math.abs(x) > 30_000_000.0D || Math.abs(z) > 30_000_000.0D) {
            if (buffer.increase(player, 2.0) > 2.0) {
                flag(data, "Out of world position");
                buffer.reset(player, 1.0);
            }
            return;
        }

        // Single-packet teleports that the server never sent. Server-driven
        // moves (pearls, chorus, corrections) arrive with teleport grace, so
        // a 12+ block jump with no grace behind it came from the client alone.
        double displacement = 0.0D;
        try {
            displacement = Math.sqrt(data.getDeltaXZ() * data.getDeltaXZ()
                    + data.getDeltaY() * data.getDeltaY());
        } catch (Throwable ignored) {
            return;
        }
        boolean graced = false;
        try {
            graced = data.isTeleportTick() || data.getTicksSinceTeleport() <= 2;
        } catch (Throwable ignored) {}
        if (displacement > 12.0D && !graced) {
            if (buffer.increase(player, 2.0) > 3.0) {
                flag(data, String.format("Impossible move %.1f", displacement));
                buffer.reset(player, 1.5);
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
