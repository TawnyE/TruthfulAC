package ret.tawny.truthful.checks.impl.packet.badpacket;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
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
 * BadPacketK - entity action spam. Same action twice within 20ms, or more
 * than 30 a second. Fingers can't sprint-toggle that fast, macros can.
 */
@CheckData(order = 'K', type = CheckType.BAD_PACKET)
public final class BadPacketK extends Check {

    private static final long REPEAT_WINDOW_MS = 20L;
    private static final long RATE_WINDOW_MS = 1000L;
    private static final int MAX_PER_SECOND = 30;

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<String, Long> lastAction = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> rates = new ConcurrentHashMap<>();

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.ENTITY_ACTION) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        WrapperPlayClientEntityAction.Action action;
        try {
            action = new WrapperPlayClientEntityAction(event).getAction();
        } catch (Throwable ignored) {
            return;
        }
        if (action == null) return;

        long now = System.currentTimeMillis();
        String key = player.getUniqueId() + ":" + action.name();
        long sinceSame = now - lastAction.getOrDefault(key, 0L);
        lastAction.put(key, now);

        Deque<Long> q = rates.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > RATE_WINDOW_MS) q.pollFirst();

        if (sinceSame < REPEAT_WINDOW_MS || q.size() > MAX_PER_SECOND) {
            if (buffer.increase(player, 1.5) > 4.0) {
                flag(data, "EntityAction spam");
                buffer.reset(player, 2.0);
            }
        } else {
            buffer.decrease(player, 0.25);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        rates.remove(event.getPlayer().getUniqueId());
        lastAction.keySet().removeIf(k -> k.startsWith(event.getPlayer().getUniqueId().toString()));
    }
}
