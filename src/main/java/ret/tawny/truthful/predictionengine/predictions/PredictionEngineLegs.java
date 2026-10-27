package ret.tawny.truthful.predictionengine.predictions;

import org.bukkit.potion.PotionEffectType;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.checks.impl.movement.MovementCheckSupport;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.predictionengine.PredictionResult;
import ret.tawny.truthful.predictionengine.VanillaMoveMath;
import ret.tawny.truthful.utils.world.Collider;
import ret.tawny.truthful.utils.world.PhysicsConstants;

/**
 * PredictionEngineLegs - the on-foot movement engine.
 *
 * Structure mirrors the vanilla client tick:
 *   1. carried velocity (from PredictionState - post friction/gravity last tick)
 *   2. thread fresh knockback into the starting velocity
 *   3. add input * frictionInfluencedSpeed for every plausible input
 *   4. resolve collisions per candidate; pick the closest
 *   5. store the winning candidate's end-of-tick velocity as the carry
 *
 * The empirical 0.88/0.63 "calibration scales" of the old engine are gone:
 * they were compensating for rebuilding velocity from the client's reported
 * delta (the PRE-friction velocity) instead of carrying it. Exact input
 * transforms, acceleration, friction and gravity live in VanillaMoveMath.
 *
 * Spear lunges are simulated as a bounded one-shot impulse along the captured
 * use direction rather than a blanket exemption.
 */
public final class PredictionEngineLegs {

    private PredictionEngineLegs() {}

    /** Keyboard-era direction table (1.20 - 1.21.1): cardinals + diagonals. */
    private static final double[][] INPUTS_BRUTE = {
            {0, 0},
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {0.7071, 0.7071}, {-0.7071, 0.7071}, {0.7071, -0.7071}, {-0.7071, -0.7071},
    };

    private static final double LUNGE_STEP = 0.1D;
    private static final double LUNGE_MAX = 4.0D;
    /** Sub-noise match on both axes: no later candidate can change the verdict. */
    private static final double EXIT_EPSILON = 1.0E-4D;

    private static final class Best {
        double xz = Double.MAX_VALUE;
        double y = Double.MAX_VALUE;
        String tagY = "none";
        double rx, ry, rz;
        int candidates;
    }

    public static PredictionResult emulate(PlayerData data,
                                           double accX, double accZ, double accY,
                                           double lastX, double lastZ, double lastY,
                                           boolean onGround, boolean wasOnGround,
                                           int airTicks,
                                           double frictionIgnored,
                                           double velX, double velY, double velZ) {
        final PredictionResult r = new PredictionResult();
        r.offsetXZ = Double.MAX_VALUE;
        r.offsetY = Double.MAX_VALUE;

        final int nowTick = data.getTicksTracked();

        final boolean knownInput = Truthful.getInstance().getVersionManager().getAdapter()
                .supportsClientTickEnd() && data.getActionTracker().isInputKnown(nowTick);
        r.knownInput = knownInput;

        final Allowance allowance = computeAllowances(data, onGround, knownInput);

        final int levitation = data.getPotionLevel(PotionEffectType.LEVITATION);
        final boolean slowFalling = data.getPotionLevel(PotionEffectType.SLOW_FALLING) > 0;
        final double gravity = data.getGravity();
        final double rawFriction = MovementCheckSupport.computeGroundFrictionRaw(data);
        final double friction = VanillaMoveMath.movementFriction(data, wasOnGround, rawFriction);

        final boolean sprint = data.isSprinting() || (knownInput && data.getActionTracker().isInputSprint());
        final boolean sneak = data.isSneaking() || (knownInput && data.getActionTracker().isInputShift());
        final double sneakMult = Math.max(0.3D, data.getSneakingSpeed());
        final boolean usingItem = data.isUsingItem() && data.isSlowItem();

        double slowMult = 1.0D;
        if (usingItem) slowMult *= VanillaMoveMath.ITEM_USE_MULTIPLIER;
        if (data.isInWeb()) slowMult *= 0.25D;
        if (data.getMovementContext().isPowderSnow() && !data.isWearingLeatherBoots()) slowMult *= 0.3D;
        r.slowMult = slowMult;

        double[] start = VanillaMoveMath.startVelocity(data, lastX, lastY, lastZ,
                wasOnGround, rawFriction, gravity, levitation, slowFalling, velX, velY, velZ);
        // Piston pushes move the client with no velocity packet. Apply the
        // recorded push DIRECTION to the starting velocity (Grim-style)
        // instead of subtracting a directionless allowance from the error.
        int sincePiston = nowTick - data.getLastPistonTick();
        if (sincePiston >= 0 && sincePiston <= 4) {
            start[0] += data.getPistonDirX() * 0.51D;
            start[1] += data.getPistonDirY() * 0.51D;
            start[2] += data.getPistonDirZ() * 0.51D;
        }
        final double startX = start[0], startY = start[1], startZ = start[2];

        final int jumpBoost = data.getPotionLevel(PotionEffectType.JUMP_BOOST);
        final double jumpPower = VanillaMoveMath.jumpPower(data);
        final double restitution = Math.max(Truthful.getInstance().getVersionManager()
                .getAdapter().getBedBounceRestitution(), data.getEntityBounciness());
        // A bed/slime bounce leaves the ground the same tick, so requiring
        // onGround meant the bounce candidate was never offered and the carry
        // diverged into an endless "fall" false flag. Key it on the recorded
        // impact instead.
        final double impactY = data.getPositionTracker().getBounceImpactY();
        final boolean impactFresh = nowTick - data.getPositionTracker().getBounceImpactTick() <= 3;
        final boolean bounceLanding = !sneak && impactFresh
                && (nowTick - data.getPositionTracker().getLastBounceCapableTick()) < 8;
        final boolean knownJump = knownInput && data.getActionTracker().isInputJump();
        // A legal jump needs real support. The client's ground claim is NOT
        // trusted (ghost-block / air-jump cheats set it falsely), and the
        // window is 1 tick of takeoff lag. Partial blocks are covered by the
        // shape-boundary / support probes. This is the air-jump bypass fix.
        final boolean supportNear = ret.tawny.truthful.utils.world.WorldUtils.hasSupportBelow(data, 0.15D)
                || ret.tawny.truthful.utils.world.WorldUtils.isAtShapeBoundary(data);
        final boolean canLeaveGround = onGround || wasOnGround || airTicks <= 1 || supportNear;
        int vs = 0;
        double[] candY = new double[32];
        String[] candTag = new String[32];
        boolean sustainedClimb = !data.isOnClimbable() && isSustainedClimb(lastY);

        if (data.isOnClimbable() || sustainedClimb) {
            candY[vs] = startY; candTag[vs++] = "fall";
            candY[vs] = (lastY - gravity) * PhysicsConstants.AIR_DRAG_Y; candTag[vs++] = "reconstruct";
            candY[vs] = 0.1176D; candTag[vs++] = "climb_std";
            candY[vs] = 0.0D; candTag[vs++] = "climb_stop";
            candY[vs] = -0.15D * PhysicsConstants.AIR_DRAG_Y; candTag[vs++] = "climb_slide";
            if (canLeaveGround && (!knownInput || knownJump || wasOnGround)) { candY[vs] = jumpPower; candTag[vs++] = "jump_takeoff"; }
        } else if (data.isInWeb()) {
            candY[vs] = startY; candTag[vs++] = "web";
            candY[vs] = 0.0D; candTag[vs++] = "web_static";
            candY[vs] = (lastY * 0.25D - gravity) * PhysicsConstants.AIR_DRAG_Y; candTag[vs++] = "web_fallish";
        } else if (levitation > 0) {
            candY[vs] = startY; candTag[vs++] = "levitation";
        } else {
            candY[vs] = startY; candTag[vs++] = "fall";
            // Gravity chain reconstructed from the client's LAST delta. Fixes
            // carried-velocity divergence at jumps/transitions without opening
            // a fly hole: gravity shrinks it every tick, so sustained flight
            // still fails.
            candY[vs] = (lastY - gravity) * PhysicsConstants.AIR_DRAG_Y; candTag[vs++] = "reconstruct";
            if (bounceLanding && impactFresh && !Double.isNaN(impactY)) {
                candY[vs] = (-impactY * restitution) + velY; candTag[vs++] = "bounce";
            }
            if (canLeaveGround && (!knownInput || knownJump || wasOnGround)) {
                candY[vs] = jumpPower + velY; candTag[vs++] = "jump_takeoff";
                // 1.21.2+ uses max(jumpPower, currentY): a bounce/jump overlap
                // keeps the higher velocity.
                candY[vs] = Math.max(jumpPower, startY) + velY; candTag[vs++] = "jump_takeoff_max";
            }
            if (onGround || wasOnGround || data.isClientGround()) {
                candY[vs] = 0.0D + velY; candTag[vs++] = "ground_rest";
            }
            if (onGround) {
                candY[vs] = -gravity * PhysicsConstants.AIR_DRAG_Y; candTag[vs++] = "ground_collapse";
            }
            if (sneak && airTicks <= 5) {
                candY[vs] = 0.0D; candTag[vs++] = "sneak_edge";
                candY[vs] = -gravity * PhysicsConstants.AIR_DRAG_Y; candTag[vs++] = "sneak_microfall";
            }
            if (data.getMovementContext().isHoney() && !onGround) {
                candY[vs] = -0.13D; candTag[vs++] = "honey_slide";
                candY[vs] = 0.0D; candTag[vs++] = "honey_stick";
            }
            if (data.getMovementContext().isPowderSnow() && !onGround) {
                candY[vs] = -0.05D; candTag[vs++] = "powder_sink";
                candY[vs] = 0.0D; candTag[vs++] = "powder_hold";
            }
        }

        // collision session
        final double px = data.getPositionTracker().getLastX();
        final double py = data.getPositionTracker().getLastY();
        final double pz = data.getPositionTracker().getLastZ();
        final double width = Math.max(0.1D, data.getPlayerWidth());
        final double height = Math.max(0.1D, data.getPlayerHeight());
        final double stepHeight = Math.max(0.0D, data.getStepHeight());

        Collider.Session session = Collider.prepare(data.getWorldCache(), px, py, pz, width, height,
                Math.min(3.0D, Math.max(0.8D, Math.abs(startX) + Math.abs(velX) + 0.75D)),
                Math.min(4.0D, Math.max(1.2D, Math.abs(startY) + Math.abs(velY) + jumpPower + 1.0D)),
                Math.min(3.0D, Math.max(0.8D, Math.abs(startZ) + Math.abs(velZ) + 0.75D)));
        Collider.Result resolved = new Collider.Result();

        final Best best = new Best();

        // Attack slow multiplies the CARRIED velocity by 0.6 (vanilla) on the
        // attack tick; offered as an alternate candidate, not a hard rule.
        boolean attackWindow = nowTick - data.getLastAttackPacketTick() <= 1;
        double[] atkScales = attackWindow ? new double[]{1.0D, 0.6D} : new double[]{1.0D};

        final double accelSprint = VanillaMoveMath.frictionInfluencedSpeed(data, wasOnGround, true, rawFriction, slowMult);
        final double accelWalk = VanillaMoveMath.frictionInfluencedSpeed(data, wasOnGround, false, rawFriction, slowMult);

        final double yaw = Math.toRadians(data.getYaw());
        final double sin = Math.sin(yaw);
        final double cos = Math.cos(yaw);

        // A lunge that arrives as a server velocity packet is already handled
        // by the replace-KB start; only offer impulse candidates when the dash
        // is client-side (no packet), or the two would double-count.
        final boolean hasFreshKb = Math.abs(velX) + Math.abs(velY) + Math.abs(velZ) > 1.0E-6D;
        final boolean lungeFresh = data.consumeLungeFresh();

        for (double atkScale : atkScales) {
            final double baseX = startX * atkScale;
            final double baseZ = startZ * atkScale;

            for (int sprintFlip = 0; sprintFlip < 2; sprintFlip++) {
                final boolean modelSprint = sprintFlip == 0 ? sprint : !sprint;
                final double accel = modelSprint ? accelSprint : accelWalk;

                if (knownInput) {
                    double[] in = inputFromKnown(data, sneak, usingItem, sneakMult);
                    double mx = (in[0] * cos - in[1] * sin) * accel;
                    double mz = (in[1] * cos + in[0] * sin) * accel;
                    evaluate(data, session, resolved, px, py, pz, width, height, stepHeight,
                            baseX + mx, baseZ + mz, candY, candTag, vs, onGround, accX, accY, accZ,
                            allowance, best, modelSprint, sin, cos);
                } else {
                    for (double[] dir : INPUTS_BRUTE) {
                        double[] in = VanillaMoveMath.inputDouble(dir[0], dir[1], sneak, sneakMult,
                                VanillaMoveMath.ITEM_USE_MULTIPLIER, usingItem);
                        double mx = (in[0] * cos - in[1] * sin) * accel;
                        double mz = (in[1] * cos + in[0] * sin) * accel;
                        evaluate(data, session, resolved, px, py, pz, width, height, stepHeight,
                                baseX + mx, baseZ + mz, candY, candTag, vs, onGround, accX, accY, accZ,
                                allowance, best, modelSprint, sin, cos);
                        if (best.xz <= EXIT_EPSILON && best.y <= EXIT_EPSILON) break;
                    }
                }
                if (best.xz <= EXIT_EPSILON && best.y <= EXIT_EPSILON) break;
            }

            // Momentum recovery candidate: the client's last delta decayed by
            // friction. Fixes carried-velocity loss (e.g. ice momentum when
            // the input bit lags) WITHOUT opening a speed hole - it is only
            // offered while the last delta is within the legal max speed.
            double lastSpd = Math.hypot(lastX, lastZ);
            if (lastSpd > 0.02D
                    && lastSpd <= MovementCheckSupport.computeMaxHorizontalSpeed(data) + 0.05D) {
                evaluate(data, session, resolved, px, py, pz, width, height, stepHeight,
                        lastX * friction, lastZ * friction, candY, candTag, vs,
                        onGround, accX, accY, accZ, allowance, best, sprint, sin, cos);
            }

            // Ladder/vine: the client's horizontal is clamped and usually
            // zero with no input; offer a zero-horizontal candidate so stored
            // momentum from before the climb does not false flag.
            if (data.isOnClimbable() || sustainedClimb) {
                evaluate(data, session, resolved, px, py, pz, width, height, stepHeight,
                        0.0D, 0.0D, candY, candTag, vs,
                        onGround, accX, accY, accZ, allowance, best, sprint, sin, cos);
            }

            // One-shot impulse candidates along the captured direction:
            // riptide has an exact strength; spear/unknown use a bounded search.
            if (lungeFresh && !hasFreshKb) {
                double strength = data.getImpulseStrength();
                if (strength > 0.0D) {
                    for (double k : new double[]{1.0D, 0.9D, 0.75D, 0.5D}) {
                        double mag = strength * k;
                        evaluateLunge(data, session, resolved, px, py, pz, width, height, stepHeight,
                                startX + data.getLungeDirX() * mag,
                                startZ + data.getLungeDirZ() * mag,
                                data.getLungeDirY() * mag,
                                candY, candTag, vs, onGround, accX, accY, accZ, allowance, best);
                    }
                } else {
                    for (double mag = LUNGE_STEP; mag <= LUNGE_MAX; mag += LUNGE_STEP) {
                        evaluateLunge(data, session, resolved, px, py, pz, width, height, stepHeight,
                                startX + data.getLungeDirX() * mag,
                                startZ + data.getLungeDirZ() * mag,
                                data.getLungeDirY() * mag,
                                candY, candTag, vs, onGround, accX, accY, accZ, allowance, best);
                    }
                }
            }
            if (best.xz <= EXIT_EPSILON && best.y <= EXIT_EPSILON) break;
        }

        if (data.isUnderBlock()) {
            double dy = Math.max(0.0D, Math.abs(accY) - allowance.y - 0.01D);
            if (dy < best.y) { best.y = dy; best.tagY = "head_bump"; }
        }

        r.offsetXZ = best.xz;
        r.offsetY = best.y;
        r.best = best.tagY;
        r.allowanceDebug = allowance.debug.toString();
        r.friction = friction;
        r.rawFriction = rawFriction;
        r.candidateCount = best.candidates;
        r.predX = best.rx;
        r.predY = best.ry;
        r.predZ = best.rz;

        double[] carry = new double[3];
        // Carry the PREDICTED candidate (never the raw client velocity: that
        // would let a hacker feed their own motion in and become explainable,
        // aborting fly/speed detection). The fixes for divergence are model
        // fixes (jump gating, momentum candidate), not re-anchoring.
        VanillaMoveMath.endOfTick(data, best.rx, best.ry, best.rz, friction, gravity, levitation, slowFalling, carry);
        // Never let the carried horizontal velocity exceed the legal ceiling -
        // prevents a wrong tick from compounding into endless over-prediction.
        double carriedCap = MovementCheckSupport.computeMaxHorizontalSpeed(data) + 0.15D;
        double carriedMag = Math.hypot(carry[0], carry[2]);
        if (carriedMag > carriedCap && carriedMag > 0.0D) {
            carry[0] *= carriedCap / carriedMag;
            carry[2] *= carriedCap / carriedMag;
        }
        r.carriedX = carry[0];
        r.carriedY = carry[1];
        r.carriedZ = carry[2];
        r.stateCarried = true;

        r.offset = Math.hypot(r.offsetXZ, r.offsetY);
        if (r.hardViolation || r.offset > 0.55D) r.hardViolation = true;

        // Air-jump / ghost-block: a positive vertical impulse while genuinely
        // unsupported, past takeoff lag, with nothing explaining it (KB,
        // levitation, fluid, climb, web, powder, bubble, geyser, bounce).
        if (airTicks >= 3 && accY > 0.20D
                && !data.isInLiquid() && !data.isGliding() && !data.isOnClimbable()
                && !data.isInWeb() && !data.getMovementContext().isPowderSnow()
                && data.getPotionLevel(PotionEffectType.LEVITATION) <= 0
                && data.getPositionTracker().getBubbleColumnDrag() == null
                && nowTick - data.getLastPistonTick() > 6
                && nowTick - data.getPositionTracker().getLastGeyserTick() > 120
                && nowTick - data.getPositionTracker().getLastBounceCapableTick() > 12
                && !data.getVelocities().hasAcknowledgedVelocity(10, true)) {
            r.hardViolation = true;
            r.offsetY = Math.max(r.offsetY, accY);
            r.best = "air_jump";
        }

        // Omni-sprint: only when genuinely not pressing forward, on a
        // non-slippery surface, at real speed and clearly opposite the look.
        // Ice momentum and mid-turn strafes were false-flagging here.
        if (data.isSprinting() && onGround && !data.hasVelocity() && data.getAirTicks() == 0
                && rawFriction <= 0.6D) {
            boolean inputForward = knownInput && data.getActionTracker().isInputForward();
            if (!inputForward) {
                double accXZ = Math.hypot(accX, accZ);
                if (accXZ > 0.20D) {
                    double fwd = -sin * accX + cos * accZ;
                    if (fwd < -0.10D) r.sprintBackwards = true;
                }
            }
        }
        return r;
    }

    private static void evaluate(PlayerData data, Collider.Session session, Collider.Result resolved,
                                 double px, double py, double pz, double width, double height, double stepHeight,
                                 double motX, double motZ, double[] candY, String[] candTag, int vs,
                                 boolean onGround, double accX, double accY, double accZ, Allowance allowance,
                                 Best best, boolean sprinting, double sin, double cos) {
        for (int v = 0; v < vs; v++) {
            double cx = motX;
            double cz = motZ;
            if (sprinting && "jump_takeoff".equals(candTag[v])) {
                cx += -sin * 0.2D;
                cz += cos * 0.2D;
            }
            session.resolve(px, py, pz, width, height,
                    VanillaMoveMath.clampMinMotion(cx), VanillaMoveMath.clampMinMotion(candY[v]),
                    VanillaMoveMath.clampMinMotion(cz), onGround, stepHeight, resolved);
            best.candidates++;
            double dxz = Math.max(0.0D, Math.hypot(accX - resolved.x, accZ - resolved.z) - allowance.xz);
            double dy = Math.max(0.0D, Math.abs(accY - resolved.y) - allowance.y);
            if (dxz < best.xz) best.xz = dxz;
            if (dy < best.y) { best.y = dy; best.tagY = candTag[v]; }
            if (dxz <= best.xz + 1.0E-9D && dy <= best.y + 1.0E-9D) {
                best.rx = resolved.x; best.ry = resolved.y; best.rz = resolved.z;
            }
        }
    }

    private static void evaluateLunge(PlayerData data, Collider.Session session, Collider.Result resolved,
                                      double px, double py, double pz, double width, double height, double stepHeight,
                                      double motX, double motZ, double motYAdd, double[] candY, String[] candTag, int vs,
                                      boolean onGround, double accX, double accY, double accZ, Allowance allowance,
                                      Best best) {
        for (int v = 0; v < vs; v++) {
            session.resolve(px, py, pz, width, height,
                    VanillaMoveMath.clampMinMotion(motX),
                    VanillaMoveMath.clampMinMotion(candY[v] + motYAdd),
                    VanillaMoveMath.clampMinMotion(motZ), onGround, stepHeight, resolved);
            best.candidates++;
            double dxz = Math.max(0.0D, Math.hypot(accX - resolved.x, accZ - resolved.z) - allowance.xz);
            double dy = Math.max(0.0D, Math.abs(accY - resolved.y) - allowance.y);
            if (dxz < best.xz) best.xz = dxz;
            if (dy < best.y) { best.y = dy; best.tagY = "lunge"; }
            if (dxz <= best.xz + 1.0E-9D && dy <= best.y + 1.0E-9D) {
                best.rx = resolved.x; best.ry = resolved.y; best.rz = resolved.z;
            }
        }
    }

    private static double[] inputFromKnown(PlayerData data, boolean sneak, boolean usingItem, double sneakMult) {
        double sideways = (data.getActionTracker().isInputLeft() ? 1 : 0)
                - (data.getActionTracker().isInputRight() ? 1 : 0);
        double forward = (data.getActionTracker().isInputForward() ? 1 : 0)
                - (data.getActionTracker().isInputBackward() ? 1 : 0);
        if (Truthful.getInstance().getVersionManager().getAdapter().supportsModernInputTransform()) {
            return VanillaMoveMath.inputModern(sideways, forward, sneak, sneakMult,
                    VanillaMoveMath.ITEM_USE_MULTIPLIER, usingItem);
        }
        return VanillaMoveMath.inputDouble(sideways, forward, sneak, sneakMult,
                VanillaMoveMath.ITEM_USE_MULTIPLIER, usingItem);
    }

    private static boolean isSustainedClimb(double lastY) {
        return Math.abs(lastY - 0.1176D) < 0.005D
                || Math.abs(lastY - 0.2D) < 0.02D
                || Math.abs(lastY + 0.15D * PhysicsConstants.AIR_DRAG_Y) < 0.005D;
    }

    private static final class Allowance {
        double xz;
        double y;
        final StringBuilder debug = new StringBuilder();
        void addXz(double v, String name) { xz += v; note(name, v); }
        void addY(double v, String name) { y += v; note(name, v); }
        void addAll(double v, String name) { xz += v; y += v; note(name, v); }
        private void note(String name, double v) {
            if (v <= 0.0D) return;
            if (debug.length() > 0) debug.append(';');
            debug.append(name).append('=').append(String.format("%.3f", v));
        }
    }

    private static Allowance computeAllowances(PlayerData data, boolean onGround, boolean knownInput) {
        Allowance a = new Allowance();
        a.addAll(knownInput ? 0.00625D
                : (Truthful.getInstance().getVersionManager().getAdapter().supportsModernInputTransform()
                        ? 0.0335D : 0.03125D), knownInput ? "input_known" : "input_brute");

        int now = data.getTicksTracked();

        int colliding = data.getPositionTracker().getNearEntityCount();
        if (colliding > 0) {
            double push = Math.min(0.25D, 0.05D * colliding);
            a.addXz(push, "entity_push");
            a.addY(push * 0.5D, "entity_push_y");
        }

        // Pistons are threaded into the starting velocity (see emulate), so no
        // directionless piston allowance is granted here any more.

        double frictionMod = data.getFrictionModifier();
        if (onGround && Math.abs(frictionMod - 1.0D) > 1.0E-4D) {
            a.addXz(Math.min(0.30D, Math.abs(frictionMod - 1.0D) * 0.25D), "friction_mod");
        }
        double airDragMod = data.getAirDragModifier();
        if (!onGround && Math.abs(airDragMod - 1.0D) > 1.0E-4D) {
            double allow = Math.min(0.30D, Math.abs(airDragMod - 1.0D) * 0.20D);
            a.addXz(allow, "air_drag_mod");
            a.addY(allow, "air_drag_mod_y");
        }

        double bounciness = data.getEntityBounciness();
        if (bounciness > 1.0E-4D) a.addY(Math.min(0.20D, bounciness * 0.10D), "bounciness");

        if (now - data.getPositionTracker().getLastStairTick() < 6) a.addY(0.06D, "stair");
        if (data.getMovementContext().isHoney()) a.addY(0.14D, "honey");
        if (data.getMovementContext().isPowderSnow()) a.addY(0.12D, "powder_snow");

        int sinceGeyser = now - data.getPositionTracker().getLastGeyserTick();
        if (sinceGeyser >= 0 && sinceGeyser < 100) a.addY(0.60D, "geyser");

        int sinceBubble = now - data.getPositionTracker().getLastBubbleColumnTick();
        if (sinceBubble >= 0 && sinceBubble < 10) a.addY(0.35D, "bubble");

        int sinceDripleaf = now - data.getPositionTracker().getLastDripleafTick();
        if (sinceDripleaf >= 0 && sinceDripleaf < 20) a.addAll(0.25D, "dripleaf");

        if (data.isSneaking()) a.addXz(0.075D, "sneak");
        if (now - data.getLastDamageTick() <= 1) a.addAll(0.60D, "damage");
        if (now - data.getLastSoulSandTick() < 8 && data.getEnchantLevel("soul_speed") == 0) a.addXz(0.15D, "soul_sand");
        if (now - data.getLastHoneyTick() < 8) a.addXz(0.15D, "honey_contact");
        if (onGround && Math.hypot(data.getDeltaX(), data.getDeltaZ()) < 0.03D) a.addXz(0.16D, "wall_mash");
        if (!data.isGliding() && now - data.getLastGlideTick() <= 3) a.addAll(0.05D, "post_glide");
        int carryGap = data.getPredictionState().gap(now);
        if (carryGap > 1) a.addAll(Math.min(0.10D, 0.02D * carryGap), "carry_gap");
        if (!data.getPredictionState().isContiguous(now)) a.addAll(0.05D, "carry_reset");

        return a;
    }
}
