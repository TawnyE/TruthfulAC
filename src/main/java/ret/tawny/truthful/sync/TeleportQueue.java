package ret.tawny.truthful.sync;

import org.bukkit.Location;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class TeleportQueue {

    private final ConcurrentLinkedDeque<Teleport> queue = new ConcurrentLinkedDeque<>();
    private final int maxEntries;
    private final long entryTtlMillis;

    public TeleportQueue(int maxEntries, long entryTtlMillis) {
        this.maxEntries = Math.max(1, maxEntries);
        this.entryTtlMillis = Math.max(1000L, entryTtlMillis);
    }

    // Timestamps of recent server teleports that were NOT our lagbacks.
    // Spamming far positions forces the server to correct constantly, and
    // every correction used to hand out a free exemption. Counting them lets
    // us cut the grace off when corrections arrive faster than any legit
    // mechanic (pearls, chorus, portals all stay way below this).
    private final ConcurrentLinkedDeque<Long> normalSends = new ConcurrentLinkedDeque<>();
    private static final long FLOOD_WINDOW_MILLIS = 5000L;
    private static final int FLOOD_MAX_NORMAL = 12;

    public void add(int id, Location location, boolean isLagback) {
        purgeExpired();
        queue.add(new Teleport(id, location, isLagback));
        while (queue.size() > maxEntries) {
            queue.pollFirst();
        }
        if (!isLagback) {
            normalSends.addLast(System.currentTimeMillis());
        }
    }

    /** True when server corrections are arriving at disabler pace. */
    public boolean isFlooding() {
        purgeExpired();
        return normalSends.size() > FLOOD_MAX_NORMAL;
    }

    public Teleport confirm(int id) {
        purgeExpired();
        Iterator<Teleport> iterator = queue.iterator();
        while (iterator.hasNext()) {
            Teleport tp = iterator.next();
            if (tp.id == id) {
                iterator.remove();
                return tp;
            }
        }
        return null;
    }

    public Teleport match(double x, double y, double z) {
        purgeExpired();
        Iterator<Teleport> iterator = queue.iterator();
        while (iterator.hasNext()) {
            Teleport tp = iterator.next();

            double dx = tp.loc.getX() - x;
            double dy = tp.loc.getY() - y;
            double dz = tp.loc.getZ() - z;
            double distSq = dx * dx + dy * dy + dz * dz;

            // Tight radius: vanilla clients land exactly on the teleport dest.
            // The old 1.5-block radius let attackers eat queued lagbacks with
            // nearby positions. 1 block is still plenty generous.
            if (distSq <= 1.0) {
                iterator.remove();
                return tp;
            }
        }
        return null;
    }

    /**
     * Non-consuming proximity test: does ANY pending teleport sit within
     * radiusSq of the claimed position? Used by the position-sanity guard -
     * a claim near a pending teleport target is an explained landing, one
     * that matches nothing is ghost traffic. match() stays the consuming
     * operation for the actual teleport accounting.
     */
    public boolean isNearPending(double x, double y, double z, double radiusSq) {
        return isNearPending(x, y, z, null, radiusSq);
    }

    public boolean isNearPending(double x, double y, double z, java.util.UUID worldUid, double radiusSq) {
        purgeExpired();
        for (Teleport tp : queue) {
            if (worldUid != null && tp.loc != null && tp.loc.getWorld() != null) {
                if (!worldUid.equals(tp.loc.getWorld().getUID())) continue;
            }
            double dx = tp.loc.getX() - x;
            double dy = tp.loc.getY() - y;
            double dz = tp.loc.getZ() - z;
            if (dx * dx + dy * dy + dz * dz <= radiusSq) return true;
        }
        return false;
    }

    public int getPendingCount() {
        return queue.size();
    }

    public void cleanup() {
        purgeExpired();
    }

    public void clear() {
        queue.clear();
        // Intentionally NOT clearing normalSends: it is the flood detector for
        // disablers, and clearing it on respawn would hand them a free reset.
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        queue.removeIf(tp -> now - tp.timestamp > entryTtlMillis);
        while (!normalSends.isEmpty() && now - normalSends.peekFirst() > FLOOD_WINDOW_MILLIS) {
            normalSends.pollFirst();
        }
    }

    public static class Teleport {
        public final int id;
        public final Location loc;
        public final long timestamp;
        public final boolean isLagback;

        public Teleport(int id, Location loc, boolean isLagback) {
            this.id = id;
            this.loc = loc;
            this.timestamp = System.currentTimeMillis();
            this.isLagback = isLagback;
        }
    }
}