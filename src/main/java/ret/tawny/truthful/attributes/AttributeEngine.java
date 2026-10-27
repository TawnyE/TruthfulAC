package ret.tawny.truthful.attributes;

import org.bukkit.potion.PotionEffectType;
import ret.tawny.truthful.data.PlayerData;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class AttributeEngine {

    private static final long HISTORY_DURATION_MS = 1500L;
    private static final double EPSILON = 1.0E-4D;

    private final PlayerData data;
    private final Deque<AttributeSnapshot> history = new ConcurrentLinkedDeque<>();

    private static final class AttributeSnapshot {
        final double walkSpeed;
        final double gravity;
        final double jumpStrength;
        final double stepHeight;
        final double movementEfficiency;
        final double waterMovementEfficiency;
        final double sneakingSpeed;
        final double scale;
        final int speedLevel;
        final int slownessLevel;
        final int jumpBoostLevel;
        final long timestamp;

        AttributeSnapshot(double walkSpeed, int speedLevel, int slownessLevel, int jumpBoostLevel) {
            this(walkSpeed, 0.08D, 0.42D, 0.6D, 0.0D, 0.0D, 0.3D, 1.0D,
                    speedLevel, slownessLevel, jumpBoostLevel);
        }

        AttributeSnapshot(double walkSpeed, double gravity, double jumpStrength, double stepHeight,
                          double movementEfficiency, double waterMovementEfficiency, double sneakingSpeed,
                          int speedLevel, int slownessLevel, int jumpBoostLevel) {
            this(walkSpeed, gravity, jumpStrength, stepHeight, movementEfficiency,
                    waterMovementEfficiency, sneakingSpeed, 1.0D,
                    speedLevel, slownessLevel, jumpBoostLevel);
        }

        AttributeSnapshot(double walkSpeed, double gravity, double jumpStrength, double stepHeight,
                          double movementEfficiency, double waterMovementEfficiency, double sneakingSpeed,
                          double scale,
                          int speedLevel, int slownessLevel, int jumpBoostLevel) {
            this.walkSpeed = walkSpeed;
            this.gravity = gravity;
            this.jumpStrength = jumpStrength;
            this.stepHeight = stepHeight;
            this.movementEfficiency = movementEfficiency;
            this.waterMovementEfficiency = waterMovementEfficiency;
            this.sneakingSpeed = sneakingSpeed;
            this.scale = Math.max(0.0625D, Math.min(16.0D, scale));
            this.speedLevel = speedLevel;
            this.slownessLevel = slownessLevel;
            this.jumpBoostLevel = jumpBoostLevel;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public AttributeEngine(final PlayerData data) {
        this.data = data;
    }

    /**
     * Records a new snapshot of the player's current attributes and speed-affecting potions.
     */
    public void record(final double walkSpeed, final int speedLevel, final int slownessLevel, final int jumpBoostLevel) {
        history.addLast(new AttributeSnapshot(walkSpeed, speedLevel, slownessLevel, jumpBoostLevel));
        cleanUp();
    }

    public void record(final double walkSpeed, final double gravity, final double jumpStrength,
                       final double stepHeight, final double movementEfficiency,
                       final double waterMovementEfficiency, final double sneakingSpeed,
                       final int speedLevel, final int slownessLevel, final int jumpBoostLevel) {
        history.addLast(new AttributeSnapshot(walkSpeed, gravity, jumpStrength, stepHeight,
                movementEfficiency, waterMovementEfficiency, sneakingSpeed,
                speedLevel, slownessLevel, jumpBoostLevel));
        cleanUp();
    }

    public void record(final double walkSpeed, final double gravity, final double jumpStrength,
                       final double stepHeight, final double movementEfficiency,
                       final double waterMovementEfficiency, final double sneakingSpeed,
                       final double scale,
                       final int speedLevel, final int slownessLevel, final int jumpBoostLevel) {
        history.addLast(new AttributeSnapshot(walkSpeed, gravity, jumpStrength, stepHeight,
                movementEfficiency, waterMovementEfficiency, sneakingSpeed, scale,
                speedLevel, slownessLevel, jumpBoostLevel));
        cleanUp();
    }

    /**
     * Cleans up snapshots older than the configured duration window.
     */
    private void cleanUp() {
        final long now = System.currentTimeMillis();
        while (!history.isEmpty()) {
            final AttributeSnapshot snap = history.peekFirst();
            if (snap == null || now - snap.timestamp <= HISTORY_DURATION_MS) {
                break;
            }
            history.pollFirst();
        }
    }

    /**
     * Gets the maximum walk speed attribute recorded in the history window.
     */
    public double getMaxWalkSpeed() {
        cleanUp();
        double maxSpeed = 0.1D;
        boolean hasElements = false;
        for (final AttributeSnapshot snap : history) {
            hasElements = true;
            if (snap.walkSpeed > maxSpeed) {
                maxSpeed = snap.walkSpeed;
            }
        }
        // Fall back to current cached value if no snapshots exist yet
        return hasElements ? maxSpeed : data.getWalkSpeedCacheRaw();
    }

    public double getMinGravity() {
        cleanUp();
        if (history.isEmpty()) return 0.08D;
        double min = Double.MAX_VALUE;
        for (final AttributeSnapshot snap : history) {
            min = Math.min(min, snap.gravity);
        }
        return min == Double.MAX_VALUE ? 0.08D : min;
    }

    public double getMaxJumpStrength() {
        cleanUp();
        double max = 0.42D;
        for (final AttributeSnapshot snap : history) {
            max = Math.max(max, snap.jumpStrength);
        }
        return max;
    }

    public double getMaxStepHeight() {
        cleanUp();
        double max = 0.6D;
        for (final AttributeSnapshot snap : history) {
            max = Math.max(max, snap.stepHeight);
        }
        return max;
    }

    public double getMaxMovementEfficiency() {
        cleanUp();
        double max = 0.0D;
        for (final AttributeSnapshot snap : history) {
            max = Math.max(max, snap.movementEfficiency);
        }
        return max;
    }

    public double getMaxWaterMovementEfficiency() {
        cleanUp();
        double max = 0.0D;
        for (final AttributeSnapshot snap : history) {
            max = Math.max(max, snap.waterMovementEfficiency);
        }
        return max;
    }

    public double getMaxSneakingSpeed() {
        cleanUp();
        double max = 0.3D;
        for (final AttributeSnapshot snap : history) {
            max = Math.max(max, snap.sneakingSpeed);
        }
        return max;
    }

    /**
     * Latest scale in the window. Used for eye/height math where the current
     * hitbox matters (jumping under blocks while scaled).
     */
    public double getLatestScale() {
        cleanUp();
        AttributeSnapshot last = history.peekLast();
        if (last != null) return last.scale;
        return data.getScaleCacheRaw();
    }

    /**
     * Max scale in the window. Used for leniency (assume the bigger hitbox
     * when checking ceilings / walls so partial clips count as head-hits).
     */
    public double getMaxScale() {
        cleanUp();
        double max = 1.0D;
        for (final AttributeSnapshot snap : history) {
            max = Math.max(max, snap.scale);
        }
        return max;
    }

    /**
     * Gets the maximum speed potion level recorded in the history window.
     */
    public int getMaxSpeedLevel() {
        cleanUp();
        int maxLevel = 0;
        for (final AttributeSnapshot snap : history) {
            if (snap.speedLevel > maxLevel) {
                maxLevel = snap.speedLevel;
            }
        }
        return maxLevel;
    }

    /**
     * Gets the minimum slowness level recorded in the history window.
     * Prevents false positives during transition where slowness is applied.
     */
    public int getMinSlownessLevel() {
        cleanUp();
        if (history.isEmpty()) return 0;
        int minLevel = Integer.MAX_VALUE;
        for (final AttributeSnapshot snap : history) {
            if (snap.slownessLevel < minLevel) {
                minLevel = snap.slownessLevel;
            }
        }
        return minLevel == Integer.MAX_VALUE ? 0 : minLevel;
    }

    /**
     * Gets the maximum jump boost level recorded in the history window.
     */
    public int getMaxJumpBoostLevel() {
        cleanUp();
        int maxLevel = 0;
        for (final AttributeSnapshot snap : history) {
            if (snap.jumpBoostLevel > maxLevel) {
                maxLevel = snap.jumpBoostLevel;
            }
        }
        return maxLevel;
    }

    public boolean hasAttributeTransition() {
        cleanUp();
        if (history.size() < 2) return false;

        double minWalk = Double.MAX_VALUE, maxWalk = 0.0D;
        double minGravity = Double.MAX_VALUE, maxGravity = 0.0D;
        double minJump = Double.MAX_VALUE, maxJump = 0.0D;
        double minScale = Double.MAX_VALUE, maxScale = 0.0D;
        int minSpeed = Integer.MAX_VALUE, maxSpeed = 0;
        int minSlow = Integer.MAX_VALUE, maxSlow = 0;
        int minJumpBoost = Integer.MAX_VALUE, maxJumpBoost = 0;

        for (final AttributeSnapshot snap : history) {
            minWalk = Math.min(minWalk, snap.walkSpeed);
            maxWalk = Math.max(maxWalk, snap.walkSpeed);
            minGravity = Math.min(minGravity, snap.gravity);
            maxGravity = Math.max(maxGravity, snap.gravity);
            minJump = Math.min(minJump, snap.jumpStrength);
            maxJump = Math.max(maxJump, snap.jumpStrength);
            minScale = Math.min(minScale, snap.scale);
            maxScale = Math.max(maxScale, snap.scale);
            minSpeed = Math.min(minSpeed, snap.speedLevel);
            maxSpeed = Math.max(maxSpeed, snap.speedLevel);
            minSlow = Math.min(minSlow, snap.slownessLevel);
            maxSlow = Math.max(maxSlow, snap.slownessLevel);
            minJumpBoost = Math.min(minJumpBoost, snap.jumpBoostLevel);
            maxJumpBoost = Math.max(maxJumpBoost, snap.jumpBoostLevel);
        }

        return maxWalk - minWalk > 0.005D
                || maxGravity - minGravity > 0.002D
                || maxJump - minJump > 0.01D
                || maxScale - minScale > 0.01D
                || maxSpeed != minSpeed
                || maxSlow != minSlow
                || maxJumpBoost != minJumpBoost;
    }

    public String getDebugTags() {
        cleanUp();
        if (history.isEmpty()) return "ATTR_DEFAULT";
        StringBuilder builder = new StringBuilder();
        if (hasAttributeTransition()) append(builder, "ATTR_TRANSITION");
        if (getMaxWalkSpeed() > 0.100D + EPSILON) append(builder, "ATTR_SPEED");
        if (getMinGravity() < 0.080D - EPSILON) append(builder, "ATTR_LOW_GRAVITY");
        if (getMaxJumpStrength() > 0.420D + EPSILON) append(builder, "ATTR_JUMP");
        if (getMaxStepHeight() > 0.600D + EPSILON) append(builder, "ATTR_STEP");
        if (getMaxMovementEfficiency() > EPSILON) append(builder, "ATTR_TERRAIN");
        if (getMaxWaterMovementEfficiency() > EPSILON) append(builder, "ATTR_WATER");
        if (getMaxSneakingSpeed() > 0.300D + EPSILON) append(builder, "ATTR_SNEAK");
        if (Math.abs(getLatestScale() - 1.0D) > 0.01D) append(builder, "ATTR_SCALE");
        return builder.length() == 0 ? "ATTR_VANILLA" : builder.toString();
    }

    private void append(StringBuilder builder, String value) {
        if (builder.length() > 0) builder.append(',');
        builder.append(value);
    }
}
