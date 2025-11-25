package ret.tawny.truthful.sync;

import org.bukkit.util.Vector;
import ret.tawny.truthful.utils.tick.ITickable;

import java.util.concurrent.ConcurrentLinkedDeque;

public final class VelocityQueue extends ConcurrentLinkedDeque<VelocityQueue.Velocity> {

    public static final class Velocity implements ITickable {
        private final Vector velocityVec;
        private int initialDelay;
        private int ticksExisted;
        private final short transactionId;
        private boolean received;

        public Velocity(final Vector velocityVec, final int initialDelay, final short transactionId) {
            this.velocityVec = velocityVec;
            this.initialDelay = initialDelay;
            this.transactionId = transactionId;
            this.ticksExisted = 0;
            this.received = false;
        }

        public Vector getVelocityVec() {
            return velocityVec;
        }

        public short getTransactionId() {
            return transactionId;
        }

        public void setReceived() {
            this.received = true;
        }

        @Override
        public void tick() {
            if (this.initialDelay > 0) {
                --this.initialDelay;
            } else {
                ++this.ticksExisted;
            }
        }

        public boolean hasReceived() {
            return this.received || this.initialDelay <= 0;
        }

        public boolean hasExpired() {
            // FIX: Reduced from 40 to 10.
            // Prevents "Damage Fly" where players self-damage to fly for 2 seconds.
            return this.ticksExisted > 10;
        }
    }

    public void confirm(short id) {
        for (Velocity v : this) {
            if (v.getTransactionId() == id) {
                v.setReceived();
                v.initialDelay = 0;
            }
        }
    }
}