package ret.tawny.truthful.sync;

import org.bukkit.util.Vector;
import ret.tawny.truthful.utils.tick.ITickable;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class VelocityQueue implements ITickable, Iterable<VelocityQueue.VelocityEntry> {

    private final ConcurrentLinkedDeque<VelocityEntry> queue = new ConcurrentLinkedDeque<>();
    private final Map<Short, VelocityEntry> startIdIndex = new ConcurrentHashMap<>();
    private final Map<Short, VelocityEntry> endIdIndex = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final long ttlMillis;

    public VelocityQueue(int maxEntries, long ttlMillis) {
        this.maxEntries = Math.max(2, maxEntries);
        this.ttlMillis = Math.max(750L, ttlMillis);
    }

    public void addVelocity(final Vector velocity, final int startTransId, final int endTransId, final boolean explosion) {
        purgeExpired();
        VelocityEntry entry = new VelocityEntry(velocity, (short) startTransId, (short) endTransId, explosion);
        queue.addLast(entry);
        startIdIndex.put((short) startTransId, entry);
        endIdIndex.put((short) endTransId, entry);
        while (queue.size() > maxEntries) {
            VelocityEntry removed = queue.pollFirst();
            if (removed != null) {
                startIdIndex.remove(removed.startId);
                endIdIndex.remove(removed.endId);
            }
        }
    }

    public void confirm(short transId) {
        VelocityEntry startEntry = startIdIndex.get(transId);
        if (startEntry != null && !startEntry.startAcked) {
            startEntry.startAcked = true;
        }
        VelocityEntry endEntry = endIdIndex.get(transId);
        if (endEntry != null && !endEntry.endAcked) {
            endEntry.endAcked = true;
            endEntry.ackTick = 0;

            // Vanilla Set Entity Velocity REPLACES the client's motion (explosions
            // notwithstanding). Once a non-explosion entry is fully acknowledged,
            // any older non-explosion impulse is dead — zero it, don't sum.
            if (!endEntry.explosion) {
                for (VelocityEntry other : queue) {
                    if (other != endEntry && !other.explosion && other.isAcked()) {
                        other.zero();
                    }
                }
            }
        }
    }

    public boolean hasActiveVelocity() {
        for (VelocityEntry entry : queue) {
            if (entry.isAcked() && entry.lengthSquared() > 1.0E-5) return true;
            if (!entry.isAcked() && !entry.isExpired(ttlMillis)) return true;
        }
        return false;
    }

    public boolean hasExplosionVelocity() {
        for (VelocityEntry entry : queue) {
            if (entry.explosion && (!entry.isExpired(ttlMillis) || entry.lengthSquared() > 1.0E-5)) return true;
        }
        return false;
    }

    /**
     * Fresh-only variant for per-tick simulation: a knockback is applied by
     * the client ONCE (the tick it arrives) and then lives inside the
     * reported motion. Re-adding the decayed entry every tick double-counts
     * it - only entries acked within the last tick are additive candidates.
     * Returns null when nothing is fresh so the (common) no-KB tick costs
     * zero allocations.
     */
    public Vector getFreshVelocityVector(int maxAckTick) {
        Vector total = null;
        for (VelocityEntry entry : queue) {
            if (entry.isReadyForPrediction() && entry.getAckTick() <= maxAckTick) {
                if (total == null) total = new Vector();
                total.add(entry.getCurrent());
            }
        }
        return total;
    }

    /** Returns the combined, recently acknowledged velocity for a check. */
    public Vector getAcknowledgedVelocityVector(int maxAckTick, boolean includeExplosions) {
        Vector total = new Vector();
        for (VelocityEntry entry : queue) {
            if (entry.isAcked() && entry.getAckTick() <= maxAckTick
                    && (includeExplosions || !entry.isExplosion())) {
                total.add(entry.getCurrent());
            }
        }
        return total;
    }

    public boolean hasAcknowledgedVelocity(int maxAckTick, boolean includeExplosions) {
        for (VelocityEntry entry : queue) {
            if (entry.isAcked() && entry.getAckTick() <= maxAckTick
                    && (includeExplosions || !entry.isExplosion())) {
                return true;
            }
        }
        return false;
    }


    public void tick() {
        tick(true);
    }

    /**
     * Advances transaction timing after checks consume the current movement
     * packet. Decay happens only after position packets, preventing a first
     * velocity response from being compared to an already-decayed impulse.
     */
    public void tick(boolean positionUpdate) {
        tick(positionUpdate, 0.91D);
    }

    public void tick(boolean positionUpdate, double horizontalFriction) {
        purgeExpired();
        for (VelocityEntry entry : queue) {
            entry.ageTick++;
            if (!entry.isAcked()) continue;
            if (positionUpdate) {
                entry.applyFriction(horizontalFriction, 0.9800000190734863D);
            }
            entry.ackTick++;
        }
        queue.removeIf(entry -> {
            if (entry.isAcked() && (entry.lengthSquared() <= 1.0E-5 || entry.ackTick > 25)) {
                startIdIndex.remove(entry.startId);
                endIdIndex.remove(entry.endId);
                return true;
            }
            return false;
        });
    }

    private void purgeExpired() {
        queue.removeIf(entry -> {
            if (entry.isExpired(ttlMillis)) {
                startIdIndex.remove(entry.startId);
                endIdIndex.remove(entry.endId);
                return true;
            }
            return false;
        });
    }

    public void clear() {
        queue.clear();
        startIdIndex.clear();
        endIdIndex.clear();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    @Override
    public Iterator<VelocityEntry> iterator() {
        return queue.iterator();
    }

    public static final class VelocityEntry {
        private final short startId;
        private final short endId;
        private final long createTime;
        private final boolean explosion;
        private final Vector current;
        private volatile boolean startAcked;
        private volatile boolean endAcked;
        private volatile int ackTick;
        private volatile int ageTick;

        private VelocityEntry(Vector init, short startId, short endId, boolean explosion) {
            this.current = init.clone();
            this.startId = startId;
            this.endId = endId;
            this.createTime = System.currentTimeMillis();
            this.explosion = explosion;
        }

        public boolean isAcked() {
            return startAcked && endAcked;
        }

        public boolean isReadyForPrediction() {
            return startAcked;
        }

        private boolean isExpired(long ttl) {
            return System.currentTimeMillis() - createTime > ttl;
        }

        public synchronized Vector getCurrent() { return current.clone(); }
        public boolean isExplosion() { return explosion; }
        public int getAckTick() { return ackTick; }
        public int getAgeTick() { return ageTick; }

        private synchronized void zero() {
            current.setX(0.0D);
            current.setY(0.0D);
            current.setZ(0.0D);
        }

        private synchronized void applyFriction(double horizontalFriction, double verticalFriction) {
            current.setX(current.getX() * horizontalFriction);
            current.setZ(current.getZ() * horizontalFriction);
            current.setY(current.getY() * verticalFriction);
            if (Math.abs(current.getX()) < 0.003D) current.setX(0.0D);
            if (Math.abs(current.getY()) < 0.003D) current.setY(0.0D);
            if (Math.abs(current.getZ()) < 0.003D) current.setZ(0.0D);
        }

        private synchronized double lengthSquared() {
            return current.lengthSquared();
        }
    }
}
