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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BadPacketB - flying-only packets 20+ in a row while actually moving.
 * Standing still sends these all day, that's normal. Sending them while
 * gliding across the map means the position packets got eaten.
 */
@CheckData(order = 'B', type = CheckType.BAD_PACKET)
public final class BadPacketB extends Check {

    private static final int MAX_FLYING_STREAK = 20;

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, Integer> streaks = new ConcurrentHashMap<>();

    @Override
    public void handleRelMove(final RelMovePacketWrapper wrapper) {
        final PlayerData data = wrapper.getPlayerData();
        if (data == null) return;
        final Player player = data.getPlayer();
        if (player == null) return;

        UUID id = player.getUniqueId();
        if (!wrapper.isPositionUpdate() && !wrapper.isRotationUpdate()) {
            int streak = streaks.merge(id, 1, Integer::sum);
            double moved;
            try {
                moved = data.getDeltaXZ();
            } catch (Throwable ignored) {
                return;
            }
            if (streak > MAX_FLYING_STREAK && moved > 0.1D) {
                if (buffer.increase(player, 1.5) > 4.0) {
                    flag(data, String.format("Flying streak %d while moving", streak));
                    buffer.reset(player, 2.0);
                }
            }
        } else {
            streaks.put(id, 0);
            buffer.decrease(player, 0.1);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        streaks.remove(event.getPlayer().getUniqueId());
    }
}
