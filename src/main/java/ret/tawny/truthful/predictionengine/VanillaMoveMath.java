package ret.tawny.truthful.predictionengine;

import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.version.ServerVersion;

/**
 * VanillaMoveMath - exact vanilla movement constants and transforms for the
 * supported range (1.20 - 26.3+, and forward), derived from the vanilla
 * client tick order. This is the clean-room reimplementation of the math
 * credited to Grim in NOTICE; no source was copied.
 *
 * Per tick the client does, in order (ground/air regime):
 *   1. carried velocity (post friction/gravity from last tick)
 *   2. add input * frictionInfluencedSpeed
 *   3. apply the 0.003 minimum-motion clamp
 *   4. move (collide) -> this tick's actual delta
 *   5. multiply x/z by friction, y by (y - gravity) * 0.98  -> carried velocity
 *
 * Version splits that matter:
 *   - 1.21.5+: modern unit-square input transform (vs the pre-1.21.5
 *     normalize-if-length>1 transform).
 *   - 26.2+: friction_modifier / air_drag_modifier / bounciness attributes,
 *     and the ground-accel formula is skipped (speed directly) when the
 *     modified block friction is <= 0.6.
 *   - 1.20.5+: jump strength comes from the JUMP_STRENGTH attribute; sprint
 *     boost is added on the takeoff tick. Before that it is the constant 0.42.
 */
public final class VanillaMoveMath {

    private VanillaMoveMath() {}

    public static final double AIR_FRICTION = 0.91D;
    public static final double VERTICAL_DRAG = 0.9800000190734863D;
    public static final double MIN_MOTION = 0.003D;
    /** 1.18.2+ movement threshold (the old "0.03" only applies to <1.18.2). */
    public static final double MOVEMENT_THRESHOLD = 0.0002D;
    public static final double SPRINT_BOOST = 0.3D;
    public static final double SPRINT_JUMP_BOOST = 0.2D;
    public static final double INPUT_SCALE = 0.98D;
    public static final double ITEM_USE_MULTIPLIER = 0.2D;

    private static ServerVersion version() {
        return ret.tawny.truthful.Truthful.getInstance().getVersionManager().getAdapter().getVersion();
    }

    private static boolean atLeast(ServerVersion v) {
        return version().isAtLeast(v);
    }

    // =====================================================================
    // FRICTION
    // =====================================================================

    /** Block friction after 26.2 friction_modifier; identity on older servers. */
    public static double modifiedFriction(PlayerData data, double blockFriction) {
        if (!atLeast(ServerVersion.V26_2)) return blockFriction;
        double mod = data.getFrictionModifier();
        return blockFriction * mod;
    }

    public static double modifiedAirDrag(PlayerData data, double drag) {
        if (!atLeast(ServerVersion.V26_2)) return drag;
        double mod = data.getAirDragModifier();
        return drag * mod;
    }

    /**
     * The friction multiplier applied to x/z at the end of the tick.
     * Vanilla keys this on the PREVIOUS tick's ground state.
     */
    public static double movementFriction(PlayerData data, boolean wasOnGround, double blockFriction) {
        double drag = modifiedAirDrag(data, AIR_FRICTION);
        return wasOnGround ? modifiedFriction(data, blockFriction) * drag : drag;
    }

    // =====================================================================
    // ACCELERATION
    // =====================================================================

    /**
     * Vanilla getFrictionInfluencedSpeed. Movement speed attribute already
     * includes potion modifiers (speed/slowness are attribute modifiers), so
     * potions are NOT reapplied here - the old engine double-counted them.
     * Sprint is a separate +30% on top of the attribute.
     */
    public static double frictionInfluencedSpeed(PlayerData data, boolean wasOnGround, boolean sprinting,
                                                 double blockFriction, double slowMult) {
        double speed = data.getWalkSpeed();
        if (sprinting) speed *= 1.0D + SPRINT_BOOST;

        if (wasOnGround) {
            double f = modifiedFriction(data, blockFriction);
            if (atLeast(ServerVersion.V26_2) && f <= 0.6D) {
                return speed * slowMult;
            }
            double f3 = Math.max(1.0E-6D, f * f * f);
            return speed * (0.21600002D / f3) * slowMult;
        }
        double base = sprinting ? 0.026D : 0.02D;
        return base * slowMult;
    }

    // =====================================================================
    // INPUT TRANSFORMS
    // =====================================================================

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    /**
     * 1.14 - 1.21.4 input transform: sneaking/use multipliers, 0.98 scale,
     * then normalize only when the length exceeds 1 (diagonals -> unit diag).
     * Returns {x, z}.
     */
    public static double[] inputDouble(double sideways, double forward, boolean slowMovement,
                                        double sneakingMult, double itemMult, boolean usingItem) {
        double x;
        double z;
        if (slowMovement) {
            x = sideways * sneakingMult;
            z = forward * sneakingMult;
        } else {
            x = clamp(sideways, -1.0D, 1.0D);
            z = clamp(forward, -1.0D, 1.0D);
        }
        if (usingItem) {
            x *= itemMult;
            z *= itemMult;
        }
        x *= INPUT_SCALE;
        z *= INPUT_SCALE;
        double len2 = x * x + z * z;
        if (len2 > 1.0D) {
            double len = Math.sqrt(len2);
            x /= len;
            z /= len;
        }
        return new double[]{x, z};
    }

    /**
     * 1.21.5+ input transform: normalize, 0.98, item/sneak, then the
     * unit-square clamp (distanceToUnitSquare). Returns {x, z}.
     */
    public static double[] inputModern(double sideways, double forward, boolean slowMovement,
                                        double sneakingMult, double itemMult, boolean usingItem) {
        double len = Math.sqrt(sideways * sideways + forward * forward);
        double x = len < 1.0E-4D ? 0.0D : sideways / len;
        double z = len < 1.0E-4D ? 0.0D : forward / len;
        x *= INPUT_SCALE;
        z *= INPUT_SCALE;
        if (usingItem) {
            x *= itemMult;
            z *= itemMult;
        }
        if (slowMovement) {
            x *= sneakingMult;
            z *= sneakingMult;
        }
        double length = Math.sqrt(x * x + z * z);
        if (length > 1.0E-8D) {
            double ux = x / length;
            double uz = z / length;
            double ax = Math.abs(ux);
            double az = Math.abs(uz);
            double additional = az > ax ? ax / az : az / ax;
            double distance = Math.sqrt(1.0D + additional * additional);
            double min = Math.min(length * distance, 1.0D);
            x = ux * min;
            z = uz * min;
        }
        return new double[]{x, z};
    }

    // =====================================================================
    // END OF TICK
    // =====================================================================

    /**
     * Applies the end-of-tick friction/gravity to a resolved movement,
     * producing the velocity vanilla carries into the next tick. Writes into
     * out[0..2] (out must be length >= 3).
     */
    public static void endOfTick(PlayerData data, double resolvedX, double resolvedY, double resolvedZ,
                                 double friction, double gravity, int levitation, boolean slowFalling,
                                 double[] out) {
        double adjustedY = resolvedY;
        if (levitation > 0) {
            adjustedY += (0.05D * levitation - resolvedY) * 0.2D;
        } else {
            double g = gravity;
            if (slowFalling && resolvedY <= 0.0D) {
                g = Math.min(g, 0.01D);
            }
            adjustedY -= g;
        }
        out[0] = resolvedX * friction;
        out[1] = adjustedY * modifiedAirDrag(data, VERTICAL_DRAG);
        out[2] = resolvedZ * friction;
    }

    /** The 0.003 minimum-motion clamp applied to x/z/y before the move. */
    public static double clampMinMotion(double v) {
        return Math.abs(v) < MIN_MOTION ? 0.0D : v;
    }

    /**
     * Vanilla FluidFallingAdjustedMovement y-step: the -0.003 surface settle
     * quirk, else subtract gravity/16 (caller passes the already-dragged y).
     */
    public static double fluidSettle(double y, double groundStep, boolean isFalling) {
        return (isFalling && Math.abs(y - 0.005D) >= 0.003D && Math.abs(y - groundStep) < 0.003D)
                ? -0.003D : y - groundStep;
    }

    /** Vanilla jump power for the player (attribute + jump boost). */
    public static double jumpPower(PlayerData data) {
        double jump = data.getJumpStrength();
        int boost = data.getPotionLevel(org.bukkit.potion.PotionEffectType.JUMP_BOOST);
        if (boost > 0) jump += 0.1D * boost;
        return jump;
    }

    // =====================================================================
    // STARTING VELOCITY (shared by every regime: legs, liquid, elytra)
    // =====================================================================

    /**
     * The velocity the current tick starts from. Vanilla carries velocity
     * continuously across regimes, so every engine shares this: the carried
     * state when it is recent, else a reconstruction from the client's last
     * (pre-friction) delta. Fresh knockback is added on top.
     */
    public static double[] startVelocity(PlayerData data,
                                         double lastX, double lastY, double lastZ,
                                         boolean wasOnGround, double rawFriction,
                                         double gravity, int levitation, boolean slowFalling,
                                         double kbX, double kbY, double kbZ) {
        // Server SetEntityVelocity REPLACES the client's motion (it is not
        // summed). Adding it to the carried velocity double-counted lunges,
        // knockback and explosions.
        if (Math.abs(kbX) + Math.abs(kbY) + Math.abs(kbZ) > 1.0E-6D) {
            return new double[]{kbX, kbY, kbZ};
        }
        final PredictionState state = data.getPredictionState();
        double sx;
        double sy;
        double sz;
        if (state.isContiguous(data.getTicksTracked())) {
            sx = state.velX;
            sy = state.velY;
            sz = state.velZ;
        } else {
            double friction = movementFriction(data, wasOnGround, rawFriction);
            double adjY = lastY;
            if (levitation > 0) {
                adjY += (0.05D * levitation - lastY) * 0.2D;
            } else {
                double g = gravity;
                if (slowFalling && lastY <= 0.0D) g = Math.min(g, 0.01D);
                adjY -= g;
            }
            sx = lastX * friction;
            sy = adjY * VERTICAL_DRAG;
            sz = lastZ * friction;
        }
        return new double[]{sx, sy, sz};
    }
}
