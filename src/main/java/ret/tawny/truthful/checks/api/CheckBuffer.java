package ret.tawny.truthful.checks.api;

import org.bukkit.entity.Player;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CheckBuffer {
    private final Map<UUID, Double> bufferMap = new ConcurrentHashMap<>();
    private final double max; // Kept for reference, though checks do their own comparisons usually

    public CheckBuffer(double max) {
        this.max = max;
    }

    /**
     * Increases the buffer and returns the NEW TOTAL value.
     * Checks use this like: if (buffer.increase(p, 1.0) > 5.0) flag();
     * Atomic merge — a read-modify-write race on concurrent packet threads
     * used to silently drop increments.
     */
    public double increase(Player player, double amount) {
        return bufferMap.merge(player.getUniqueId(), amount,
                (old, add) -> Math.min(100.0, old + add));
    }

    public void decrease(Player player, double amount) {
        bufferMap.compute(player.getUniqueId(), (k, v) -> {
            if (v == null || v <= amount) return null; // don't store zeros
            return v - amount;
        });
    }

    public void reset(Player player, double value) {
        bufferMap.put(player.getUniqueId(), value);
    }

    /**
     * Gets the current buffer value for debugging.
     */
    public double get(Player player) {
        return bufferMap.getOrDefault(player.getUniqueId(), 0.0);
    }

    public void remove(Player player) {
        bufferMap.remove(player.getUniqueId());
    }
}