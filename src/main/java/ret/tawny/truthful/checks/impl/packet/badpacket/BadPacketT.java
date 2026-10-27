package ret.tawny.truthful.checks.impl.packet.badpacket;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.api.Check;
import ret.tawny.truthful.checks.api.CheckBuffer;
import ret.tawny.truthful.checks.api.data.CheckData;
import ret.tawny.truthful.checks.api.data.CheckType;
import ret.tawny.truthful.data.PlayerData;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BadPacketT - answers keepalives nobody sent. Self-calibrating: replies are
 * only compared against what the server actually asked in the window, so a
 * chatty server can never false flag. A flat per-second cap did exactly that.
 */
@CheckData(order = 'T', type = CheckType.BAD_PACKET)
public final class BadPacketT extends Check {

    private static final long WINDOW_MS = 10_000L;
    private static final long SERVER_WINDOW_MS = 15_000L;
    private static final int GRACE = 3;
    private static final int FLOOR = 5;

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, Deque<Long>> replies = new ConcurrentHashMap<>();
    private static final Map<UUID, Deque<Long>> serverSends = new ConcurrentHashMap<>();

    /** Called from the send path for every server keepalive. */
    public static void noteServerKeepAlive(UUID id) {
        if (id == null) return;
        long now = System.currentTimeMillis();
        Deque<Long> q = serverSends.computeIfAbsent(id, k -> new ArrayDeque<>());
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > SERVER_WINDOW_MS) q.pollFirst();
    }

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.KEEP_ALIVE) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        long now = System.currentTimeMillis();
        Deque<Long> q = replies.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();

        Deque<Long> sent = serverSends.get(player.getUniqueId());
        if (sent != null) {
            while (!sent.isEmpty() && now - sent.peekFirst() > SERVER_WINDOW_MS) sent.pollFirst();
        }
        int asked = sent != null ? sent.size() : 0;

        if (q.size() > FLOOR && q.size() > asked + GRACE) {
            if (buffer.increase(player, 1.5) > 5.0) {
                flag(data, String.format("KeepAlive spam %d replies/%d asked", q.size(), asked));
                buffer.reset(player, 2.5);
            }
        } else {
            buffer.decrease(player, 0.25);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        replies.remove(event.getPlayer().getUniqueId());
        serverSends.remove(event.getPlayer().getUniqueId());
    }
}
