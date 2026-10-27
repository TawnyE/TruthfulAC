package ret.tawny.truthful.checks.impl.combat.autoclicker;

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

@CheckData(order = 'C', type = CheckType.AUTOCLICKER)
public final class AutoClickerC extends Check {

    private final CheckBuffer buffer = new CheckBuffer(10.0);
    private final Map<UUID, Deque<Long>> clickTimestamps = new ConcurrentHashMap<>();

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            final Player player = (Player) event.getPlayer();
            if (Truthful.getInstance().isBedrockPlayer(player)) return;

            final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
            if (data == null || data.isServerFrozen() || data.isExempt() || data.getActionTracker().isDigging()) return;
            if (!Truthful.getInstance().getConfiguration().shouldCountGroundPunches()
                    && data.getTicksTracked() - data.getLastAttackPacketTick() > 2) return;

            long now = System.nanoTime() / 1_000_000L; // monotonic clock for CPS windows
            Deque<Long> clicks = clickTimestamps.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());

            clicks.addLast(now);

            while (!clicks.isEmpty() && (now - clicks.peekFirst()) > 1000) {
                clicks.pollFirst();
            }

            int rawClicks = clicks.size();
            double limit = Truthful.getInstance().getConfiguration().getAutoClickerMaxCps();

            double tps = Truthful.getInstance().getTps();
            if (tps < 19.0) {
                limit *= (20.0 / Math.max(1.0, tps));
            }

            if (rawClicks > limit) {
                double over = rawClicks - limit;

                if (buffer.increase(player, 1.0 + (over * 0.5)) > 15.0) {
                    flag(data, String.format("High CPS (Hard Limit). CPS: %d, Limit: %.1f", rawClicks, limit));
                    buffer.reset(player, 10.0);
                }
            } else {
                buffer.decrease(player, 0.4);
            }
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        clickTimestamps.remove(event.getPlayer().getUniqueId());
    }
}
