package ret.tawny.truthful.compensation;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import ret.tawny.truthful.utils.tick.ITickable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CompensationTracker implements ITickable {

    // Map of Entity -> History
    private final HashMap<Entity, CompensatedEntity> compensationMap;

    public CompensationTracker() {
        this.compensationMap = new HashMap<>();
    }

    public HashMap<Entity, CompensatedEntity> getCompensationMap() {
        return compensationMap;
    }

    @Override
    public void tick() {
        // Clean up logged out players
        compensationMap.keySet().removeIf(entity -> !entity.isValid() || (entity instanceof Player && !((Player) entity).isOnline()));

        final int maxHistorySize = 20; // Store last 1 second (20 ticks)
        final int currentTick = Bukkit.getCurrentTick();

        // Track all online players
        for (Player player : Bukkit.getOnlinePlayers()) {
            CompensatedEntity data = compensationMap.computeIfAbsent(player, k -> new CompensatedEntity());
            data.tick(maxHistorySize, currentTick, player.getLocation());
        }
    }

    /**
     * Tracked Entity History
     */
    public static final class CompensatedEntity {
        // Maps ServerTick -> Location
        private final LinkedHashMap<Integer, Location> history;

        public CompensatedEntity() {
            this.history = new LinkedHashMap<>();
        }

        public void tick(final int cap, final int tick, final Location location) {
            this.cull(cap);
            this.history.put(tick, location);
        }

        /**
         * Get the location of this entity at a specific server tick in the past.
         */
        public Location getLocationAt(int currentServerTick, int pingInTicks) {
            int targetTick = currentServerTick - pingInTicks;

            // Try to get exact match
            if (history.containsKey(targetTick)) {
                return history.get(targetTick);
            }

            // Fallback: Find closest tick (basic interpolation)
            // Since this is a simple map, we just return the oldest if lag is huge,
            // or the newest if lag is negative (impossible but safe).
            if (!history.isEmpty()) {
                // If we requested a time older than what we have, return oldest
                Integer oldestTick = history.keySet().iterator().next();
                if (targetTick < oldestTick) return history.get(oldestTick);

                // Otherwise return most recent (best guess)
                return new ArrayList<>(history.values()).get(history.size() - 1);
            }

            return null;
        }

        private void cull(final int cap) {
            while (history.size() > cap) {
                Integer firstKey = history.keySet().iterator().next();
                history.remove(firstKey);
            }
        }
    }

    // Helper import for the fallback logic
    private java.util.ArrayList<Location> toList(java.util.Collection<Location> c) {
        return new java.util.ArrayList<>(c);
    }
}