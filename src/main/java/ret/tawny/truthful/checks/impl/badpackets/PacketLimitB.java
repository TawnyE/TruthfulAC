package ret.tawny.truthful.checks.impl.badpackets;

import com.comphenix.protocol.events.PacketEvent;
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

@CheckData(order = 'B', type = CheckType.BAD_PACKET)
@SuppressWarnings("unused")
public final class PacketLimitB extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    // Fix: Use a map to track packet counts per player instead of a global variable
    private final Map<UUID, PacketCounter> packetMap = new ConcurrentHashMap<>();

    @Override
    public void handlePacketPlayerReceive(final PacketEvent event) {
        final Player player = event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);

        if (data == null)
            return;

        // --- FIX: JOIN EXEMPTION ---
        // Clients send massive bursts of packets on join (Settings, Recipes, Tags).
        // Ignore packet limits for the first 5 seconds (100 ticks).
        if (data.getTicksTracked() < 100) return;

        PacketCounter counter = packetMap.computeIfAbsent(player.getUniqueId(), k -> new PacketCounter());

        counter.packets++;
        long now = System.currentTimeMillis();

        if (now - counter.lastClear > 1000) {
            // Reset every second
            // Vanilla client ~20-50/s (Join bursts can be 300+).
            // Nuke/Timer/Regen often > 100/s.

            // Limit 80 is generous for normal gameplay
            if (counter.packets > 80) {
                if (buffer.increase(player, 1.0) > 3.0) {
                    flag(data, "Excessive Packets: " + counter.packets + "/s");
                }
            } else {
                buffer.decrease(player, 0.5);
            }
            counter.packets = 0;
            counter.lastClear = now;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        packetMap.remove(event.getPlayer().getUniqueId());
        buffer.remove(event.getPlayer());
    }

    // Simple container for per-player data
    private static class PacketCounter {
        int packets = 0;
        long lastClear = System.currentTimeMillis();
    }
}