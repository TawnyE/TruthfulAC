package ret.tawny.truthful.predictionengine;

import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.version.ServerVersion;

/**
 * PredictionState - the velocity the simulation CARRIES between ticks.
 *
 * This is the single biggest correctness fix over the old engine. Vanilla
 * carries the post-friction velocity from tick to tick, but the old engine
 * recomputed every tick from the client's last reported delta (which is the
 * PRE-friction velocity). The mismatch was papered over with empirical
 * "calibration scales" (0.88 / 0.63) that also happened to excuse partial
 * speed and knockback cheats. We now keep the simulated velocity, exactly
 * like Grim/Intave, and the calibration fudge is gone.
 *
 * Kept per player, reset on teleport/respawn/world change/vehicle change.
 */
public final class PredictionState {

    /** Carried velocity for the next tick (already had end-of-tick friction/gravity). */
    public double velX;
    public double velY;
    public double velZ;

    /** True once a trustworthy carried velocity exists. */
    public boolean valid;

    /** Last tick the engine updated this state (ticksTracked epoch). */
    public int lastTick = Integer.MIN_VALUE;

    /** Whether the spear-lunge impulse seed still needs to be offered as a candidate. */
    public boolean lungeFresh;

    public void reset() {
        velX = 0.0D;
        velY = 0.0D;
        velZ = 0.0D;
        valid = false;
        lastTick = Integer.MIN_VALUE;
        lungeFresh = false;
    }

    /** Stores the resolved velocity that vanilla would carry into the next tick. */
    public void carry(double x, double y, double z, int tick) {
        this.velX = x;
        this.velY = y;
        this.velZ = z;
        this.valid = true;
        this.lastTick = tick;
    }

    /**
     * True if the carried velocity is recent enough to continue from.
     * The engine runs on position packets; rotation/flying-only packets
     * interleave, so a gap of a few ticks is normal and still carried
     * (the client carries its velocity continuously).
     */
    public boolean isContiguous(int tick) {
        return valid && tick > lastTick && tick - lastTick <= 4;
    }

    /** Gap in ticks since the carried velocity was produced (>=1). */
    public int gap(int tick) {
        return valid ? tick - lastTick : Integer.MAX_VALUE;
    }
}
