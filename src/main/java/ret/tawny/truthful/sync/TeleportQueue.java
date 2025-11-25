package ret.tawny.truthful.sync;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class TeleportQueue {

    private final ConcurrentLinkedDeque<Teleport> queue = new ConcurrentLinkedDeque<>();

    public void add(Location location) {
        queue.add(new Teleport(location));
    }

    /**
     * Checks if the given coordinates match a pending teleport in the queue.
     * If a match is found, it removes the teleport and returns true.
     */
    public boolean match(double x, double y, double z) {
        // We use an iterator to safely remove the item if found
        var iterator = queue.iterator();

        while (iterator.hasNext()) {
            Teleport tp = iterator.next();

            // Check distance. Clients sometimes snap to grid, so we allow a small error (0.05 blocks)
            double distSq = Math.pow(tp.x - x, 2) + Math.pow(tp.y - y, 2) + Math.pow(tp.z - z, 2);

            if (distSq < 0.0025) { // 0.05 * 0.05
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    // Cleanup old teleports that were never confirmed (lagged out/cancelled)
    public void cleanup() {
        long now = System.currentTimeMillis();
        queue.removeIf(tp -> now - tp.timestamp > 5000); // Remove after 5 seconds
    }

    private static class Teleport {
        final double x, y, z;
        final long timestamp;

        Teleport(Location loc) {
            this.x = loc.getX();
            this.y = loc.getY();
            this.z = loc.getZ();
            this.timestamp = System.currentTimeMillis();
        }
    }
}