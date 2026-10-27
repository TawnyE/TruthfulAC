package ret.tawny.truthful.checks.impl.combat.killaura;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
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
 * KillAuraH: Attack Interval Analysis
 */
@CheckData(order = 'H', type = CheckType.KILLAURA)
public final class KillAuraH extends Check {

    private static final int SAMPLE_SIZE = 15;
    private static final double VARIANCE_THRESHOLD = 25.0; // ms^2 - human variance is typically > 100
    private static final double QUANTIZATION_THRESHOLD = 0.85; // 85% of intervals matching a quantum

    private final CheckBuffer buffer = new CheckBuffer(15.0);
    private final Map<UUID, IntervalData> dataMap = new ConcurrentHashMap<>();

    @Override
    public void handlePacketPlayerReceive(final PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY)
            return;

        WrapperPlayClientInteractEntity interact = new WrapperPlayClientInteractEntity(event);
        if (interact.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK)
            return;

        final Player player = (Player) event.getPlayer();
        final PlayerData data = Truthful.getInstance().getDataManager().getPlayerData(player);
        if (data == null || data.isExempt())
            return;

        // nanoTime is monotonic and not subject to the 15.6ms granularity of
        // the Windows default tick clock (which literally manufactures the
        // low-variance/quantized interval pattern this check flags).
        long now = System.nanoTime() / 1_000_000L;
        IntervalData intervalData = dataMap.computeIfAbsent(player.getUniqueId(), k -> new IntervalData());

        if (intervalData.lastAttackTime > 0) {
            long interval = now - intervalData.lastAttackTime;

            // Only track reasonable intervals (50ms to 500ms = 2-20 CPS)
            if (interval >= 50 && interval <= 500) {
                intervalData.addInterval(interval);
            }
        }
        intervalData.lastAttackTime = now;

        if (!intervalData.isReady())
            return;

        // === Analysis 1: Variance Check ===
        double variance = intervalData.getVariance();
        boolean lowVariance = variance < VARIANCE_THRESHOLD;

        // === Analysis 2: Quantization Check ===
        // Check if intervals cluster around specific values (e.g., 50ms, 100ms)
        double quantizationScore = intervalData.getQuantizationScore();
        boolean quantized = quantizationScore > QUANTIZATION_THRESHOLD;

        // === Analysis 3: Perfect CPS ===
        // Check for unnaturally consistent CPS (e.g., exactly 20.0 CPS)
        double avgInterval = intervalData.getAverage();
        double cps = 1000.0 / avgInterval;
        boolean perfectCps = Math.abs(cps - Math.round(cps)) < 0.05; // Within 0.05 of whole number

        int suspicionLevel = 0;
        if (lowVariance)
            suspicionLevel++;
        if (quantized)
            suspicionLevel++;
        if (perfectCps && cps >= 10)
            suspicionLevel++; // Only flag high CPS

        if (suspicionLevel >= 2) {
            double severity = suspicionLevel * 1.0;
            if (buffer.increase(player, severity) > 10.0) {
                flag(data, String.format("Attack Pattern. Var: %.1f, Quant: %.2f, CPS: %.1f",
                        variance, quantizationScore, cps));
                buffer.reset(player, 5.0);
            }
        } else {
            buffer.decrease(player, 0.4);
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        buffer.remove(event.getPlayer());
        dataMap.remove(event.getPlayer().getUniqueId());
    }

    private static class IntervalData {
        long lastAttackTime = 0;
        private final Deque<Long> intervals = new ArrayDeque<>();

        void addInterval(long interval) {
            intervals.addLast(interval);
            while (intervals.size() > SAMPLE_SIZE) {
                intervals.pollFirst();
            }
        }

        boolean isReady() {
            return intervals.size() >= SAMPLE_SIZE;
        }

        double getAverage() {
            long sum = 0;
            for (Long interval : intervals) {
                sum += interval;
            }
            return intervals.isEmpty() ? 0.0 : (double) sum / intervals.size();
        }

        double getVariance() {
            double mean = getAverage();
            double sum = 0.0;
            for (Long interval : intervals) {
                double diff = interval - mean;
                sum += diff * diff;
            }
            return intervals.isEmpty() ? 0.0 : sum / intervals.size();
        }

        double getQuantizationScore() {
            // Count how many intervals are close to multiples of 50ms (common auto-clicker
            // quantum)
            int quantized = 0;
            for (Long interval : intervals) {
                // Check if interval is within 5ms of a 50ms multiple
                if (interval % 50 < 5 || interval % 50 > 45) {
                    quantized++;
                }
            }
            return intervals.isEmpty() ? 0.0 : (double) quantized / intervals.size();
        }
    }
}
