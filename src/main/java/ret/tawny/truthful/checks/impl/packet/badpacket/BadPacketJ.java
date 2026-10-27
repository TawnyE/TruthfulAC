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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BadPacketJ - swaps hotbar slots right as blocks go down. Auto-tool cheats
 * swap to the right tool per block faster than scrolling allows.
 */
@CheckData(order = 'J', type = CheckType.BAD_PACKET)
public final class BadPacketJ extends Check {

    private static final long COMBO_WINDOW_MS = 55L;

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, Long> lastPlace = new ConcurrentHashMap<>();

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        Object type = event.getPacketType();
        if (!(event.getPlayer() instanceof Player player)) return;

        if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            lastPlace.put(player.getUniqueId(), System.currentTimeMillis());
            return;
        }

        if (type != PacketType.Play.Client.HELD_ITEM_CHANGE) return;
        PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isServerFrozen()) return;

        long sincePlace = System.currentTimeMillis() - lastPlace.getOrDefault(player.getUniqueId(), 0L);
        if (sincePlace < COMBO_WINDOW_MS) {
            if (buffer.increase(player, 1.0) > 5.0) {
                flag(data, "Slot swap while placing");
                buffer.reset(player, 2.5);
            }
        } else {
            buffer.decrease(player, 0.1);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        lastPlace.remove(event.getPlayer().getUniqueId());
    }
}
