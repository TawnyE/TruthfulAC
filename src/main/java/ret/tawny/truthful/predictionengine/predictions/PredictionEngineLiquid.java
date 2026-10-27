package ret.tawny.truthful.predictionengine.predictions;

import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import ret.tawny.truthful.Truthful;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.predictionengine.PredictionResult;
import ret.tawny.truthful.predictionengine.VanillaMoveMath;
import ret.tawny.truthful.utils.world.Collider;
import ret.tawny.truthful.utils.world.PhysicsConstants;
import ret.tawny.truthful.utils.world.WorldUtils;

/**
 * PredictionEngineLiquid - water & lava travel, exact vanilla tick order.
 *
 * The old implementation baked the end-of-tick drag into the vertical
 * candidate, so drag applied twice and entering water flagged instantly.
 * Correct order per tick:
 *   1. start velocity (carried, or a server SetEntityVelocity which REPLACES)
 *   2. swim-pose vertical transform: y += (lookY - y) * scalar
 *   3. add input * swimSpeed (x/z); jumping adds +0.04, edge jump a full jump
 *   4. collide -> this tick's delta
 *   5. END OF TICK: x,z *= swimFriction; y *= 0.8; then the fluid settle
 *      (sprinting skips the settle)
 */
public final class PredictionEngineLiquid {

    private PredictionEngineLiquid() {}

    private static final double[][] INPUTS = {
            {0, 0}, {0, 1}, {0, -1}, {1, 0}, {-1, 0},
            {0.7071, 0.7071}, {-0.7071, 0.7071}, {0.7071, -0.7071}, {-0.7071, -0.7071},
    };

    private static final class Probe {
        double xz = Double.MAX_VALUE;
        double y = Double.MAX_VALUE;
        int idx;
        double rx, ry, rz;
    }

    public static PredictionResult emulate(PlayerData data,
                                          double accX, double accZ, double accY,
                                          double lastX, double lastZ, double lastY,
                                          boolean onGround, boolean inLava) {
        final PredictionResult r = new PredictionResult();
        r.offsetXZ = Double.MAX_VALUE;
        r.offsetY = Double.MAX_VALUE;

        final int nowTick = data.getTicksTracked();
        final double gravity = data.getGravity();
        final int levitation = data.getPotionLevel(PotionEffectType.LEVITATION);
        final boolean slowFalling = data.getPotionLevel(PotionEffectType.SLOW_FALLING) > 0;
        final boolean sprint = data.isSprinting();
        final boolean isFalling = accY <= 0.0D;

        double velX = 0, velY = 0, velZ = 0;
        Vector vel = data.getVelocities().getFreshVelocityVector(1);
        if (vel != null) { velX = vel.getX(); velY = vel.getY(); velZ = vel.getZ(); }

        double[] start = VanillaMoveMath.startVelocity(data, lastX, lastY, lastZ,
                false, 0.6D, gravity, levitation, slowFalling, velX, velY, velZ);
        final double startX = start[0], startY = start[1], startZ = start[2];

        final double gStep = gravity / 16.0D;
        final double fluidHeight = inLava ? WorldUtils.fluidHeightAt(data, true)
                : WorldUtils.fluidHeightAt(data, false);

        final double accel;
        final double frictionXZ;
        final double dragY;
        if (inLava) {
            accel = 0.02D;
            frictionXZ = 0.5D;
            dragY = fluidHeight <= 0.4D ? 0.8D : 0.5D;
        } else {
            int ds = data.getEnchantLevel("depth_strider");
            if (!data.isLastGround()) ds = (int) Math.floor(ds * 0.5D);
            final double divisor = Truthful.getInstance().getVersionManager().getAdapter().getVersion()
                    .isAtLeast(ret.tawny.truthful.version.ServerVersion.V1_21_2) ? 1.0D : 3.0D;
            double speed = 0.02D;
            double fric = sprint ? 0.9D : 0.8D;
            if (ds > 0) {
                speed += (data.getWalkSpeed() - speed) * ds / divisor;
                fric += (0.54600006D - fric) * ds / divisor;
            }
            if (data.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) fric = 0.96D;
            accel = speed;
            frictionXZ = fric;
            dragY = 0.8D;
        }

        // vertical candidates = PRE-drag velocities for this tick
        int vs = 0;
        double[] candY = new double[24];
        String[] candTag = new String[24];
        final double lookY = -Math.sin(Math.toRadians(data.getPitch()));
        final boolean swimPose = data.isSwimming() || data.isInLiquid();

        if (inLava) {
            candY[vs] = startY; candTag[vs++] = "lava";
            candY[vs] = startY + 0.04D; candTag[vs++] = "lava_climb";
            candY[vs] = (lastY - gravity) * PhysicsConstants.AIR_DRAG_Y; candTag[vs++] = "reconstruct";
            if (fluidHeight <= 0.4D && data.isLastGround() && !onGround) {
                candY[vs] = data.getJumpStrength(); candTag[vs++] = "jump_out";
            }
        } else {
            if (swimPose) {
                double scalar = lookY < -0.2D ? 0.085D : 0.06D;
                candY[vs] = startY + (lookY - startY) * scalar; candTag[vs++] = "swim_look";
                candY[vs] = startY; candTag[vs++] = "surface_swim";
            } else {
                candY[vs] = startY; candTag[vs++] = "water";
            }
            candY[vs] = startY + 0.04D; candTag[vs++] = "swim_up";
            candY[vs] = (lastY - gravity) * PhysicsConstants.AIR_DRAG_Y; candTag[vs++] = "reconstruct";
            if (fluidHeight > 0.0D && fluidHeight <= 0.4D && data.isLastGround() && !onGround) {
                candY[vs] = data.getJumpStrength(); candTag[vs++] = "jump_out";
            }
            if (!data.isNearVehicle() && (Math.abs(accX) + Math.abs(accZ)) > 0.003D && fluidHeight < 0.9D) {
                candY[vs] = 0.3D; candTag[vs++] = "swim_hop";
            }
            Boolean bubble = data.getPositionTracker().getBubbleColumnDrag();
            if (bubble != null) {
                candY[vs] = bubble ? Math.max(-0.49D, startY - 0.06D)
                        : Math.min(0.7D, startY + 0.06D);
                candTag[vs++] = bubble ? "bubble_down" : "bubble_up";
            }
        }

        final double px = data.getPositionTracker().getLastX();
        final double py = data.getPositionTracker().getLastY();
        final double pz = data.getPositionTracker().getLastZ();
        final double width = Math.max(0.1D, data.getPlayerWidth());
        final double height = Math.max(0.1D, data.getPlayerHeight());
        Collider.Session session = Collider.prepare(data.getWorldCache(), px, py, pz, width, height,
                Math.min(3.0D, Math.abs(startX) + 0.8D), 3.0D, Math.min(3.0D, Math.abs(startZ) + 0.8D));
        Collider.Result resolved = new Collider.Result();
        Probe p = new Probe();

        final boolean knownInput = Truthful.getInstance().getVersionManager().getAdapter()
                .supportsClientTickEnd() && data.getActionTracker().isInputKnown(nowTick);
        final double yaw = Math.toRadians(data.getYaw());
        final double sin = Math.sin(yaw), cos = Math.cos(yaw);
        final boolean sneak = data.isSneaking();
        final boolean usingItem = data.isUsingItem() && data.isSlowItem();
        final double sneakMult = Math.max(0.3D, data.getSneakingSpeed());

        double bestXZ = Double.MAX_VALUE, bestY = Double.MAX_VALUE;
        String bestTag = "water";
        double brx = 0, bry = 0, brz = 0;

        for (int sprintFlip = 0; sprintFlip < 2; sprintFlip++) {
            final boolean modelSprint = sprintFlip == 0 ? sprint : !sprint;
            final double a = modelSprint ? accel : accel * 0.8D;

            if (knownInput) {
                double[] in = inputFromKnown(data, sneak, usingItem, sneakMult);
                double mx = (in[0] * cos - in[1] * sin) * a;
                double mz = (in[1] * cos + in[0] * sin) * a;
                probe(session, resolved, p, px, py, pz, width, height, startX + mx, startZ + mz, candY, vs, accX, accY, accZ);
                if (p.xz < bestXZ) bestXZ = p.xz;
                if (p.y < bestY) { bestY = p.y; bestTag = candTag[p.idx]; }
                if (p.xz <= bestXZ + 1.0E-9D && p.y <= bestY + 1.0E-9D) { brx = p.rx; bry = p.ry; brz = p.rz; }
            } else {
                for (double[] dir : INPUTS) {
                    double[] in = VanillaMoveMath.inputDouble(dir[0], dir[1], sneak, sneakMult,
                            VanillaMoveMath.ITEM_USE_MULTIPLIER, usingItem);
                    double mx = (in[0] * cos - in[1] * sin) * a;
                    double mz = (in[1] * cos + in[0] * sin) * a;
                    probe(session, resolved, p, px, py, pz, width, height, startX + mx, startZ + mz, candY, vs, accX, accY, accZ);
                    if (p.xz < bestXZ) bestXZ = p.xz;
                    if (p.y < bestY) { bestY = p.y; bestTag = candTag[p.idx]; }
                    if (p.xz <= bestXZ + 1.0E-9D && p.y <= bestY + 1.0E-9D) { brx = p.rx; bry = p.ry; brz = p.rz; }
                }
            }
        }

        // momentum-kill (water entry / stationary against a wall)
        probe(session, resolved, p, px, py, pz, width, height, 0.0D, 0.0D, candY, vs, accX, accY, accZ);
        if (p.xz < bestXZ) bestXZ = p.xz;
        if (p.y < bestY) { bestY = p.y; bestTag = "water_stop"; }
        if (p.xz <= bestXZ + 1.0E-9D && p.y <= bestY + 1.0E-9D) { brx = p.rx; bry = p.ry; brz = p.rz; }

        // one-shot impulse (riptide / client-side lunge) when no server KB
        final boolean hasFreshKb = vel != null
                && (Math.abs(velX) + Math.abs(velY) + Math.abs(velZ) > 1.0E-6D);
        if (data.consumeLungeFresh() && !hasFreshKb) {
            double strength = data.getImpulseStrength();
            double[] mags;
            if (strength > 0.0D) {
                mags = new double[]{strength, strength * 0.9D, strength * 0.75D, strength * 0.5D};
            } else {
                mags = new double[40];
                for (int i = 0; i < 40; i++) mags[i] = 0.1D * (i + 1);
            }
            for (double mag : mags) {
                probe(session, resolved, p, px, py, pz, width, height,
                        startX + data.getLungeDirX() * mag, startZ + data.getLungeDirZ() * mag,
                        candY, vs, accX, accY, accZ);
                if (p.xz < bestXZ) bestXZ = p.xz;
                if (p.y < bestY) { bestY = p.y; bestTag = "impulse"; }
                if (p.xz <= bestXZ + 1.0E-9D && p.y <= bestY + 1.0E-9D) { brx = p.rx; bry = p.ry; brz = p.rz; }
            }
        }

        if (!inLava && isFlowingWaterAt(data)) bestXZ = Math.max(0.0D, bestXZ - 0.025D);
        Boolean bubble = data.getPositionTracker().getBubbleColumnDrag();
        if (bubble != null) bestY = Math.max(0.0D, bestY - 0.10D);

        r.offsetXZ = bestXZ;
        r.offsetY = bestY;
        r.best = bestTag;
        r.predX = brx; r.predY = bry; r.predZ = brz;
        r.offset = Math.hypot(bestXZ, bestY);
        r.hardViolation = r.offset > 1.2D;

        // END OF TICK drag + settle on the PREDICTED delta
        double carryX, carryY, carryZ;
        if (inLava) {
            carryX = brx * 0.5D;
            carryZ = brz * 0.5D;
            carryY = fluidHeight <= 0.4D
                    ? VanillaMoveMath.fluidSettle(bry * 0.8D, gStep, isFalling)
                    : bry * 0.5D;
            carryY -= gravity / 4.0D;
        } else {
            carryX = brx * frictionXZ;
            carryZ = brz * frictionXZ;
            carryY = bry * dragY;
            if (!sprint) carryY = VanillaMoveMath.fluidSettle(carryY, gStep, isFalling);
        }
        r.carriedX = carryX;
        r.carriedY = carryY;
        r.carriedZ = carryZ;
        r.stateCarried = true;
        return r;
    }

    private static void probe(Collider.Session session, Collider.Result resolved, Probe out,
                              double px, double py, double pz, double width, double height,
                              double motX, double motZ, double[] candY, int vs,
                              double accX, double accY, double accZ) {
        out.xz = Double.MAX_VALUE;
        out.y = Double.MAX_VALUE;
        out.idx = 0;
        out.rx = out.ry = out.rz = 0;
        for (int v = 0; v < vs; v++) {
            session.resolve(px, py, pz, width, height,
                    VanillaMoveMath.clampMinMotion(motX), VanillaMoveMath.clampMinMotion(candY[v]),
                    VanillaMoveMath.clampMinMotion(motZ), false, 0.0D, resolved);
            double dxz = Math.hypot(accX - resolved.x, accZ - resolved.z);
            double dy = Math.abs(accY - resolved.y);
            if (dxz < out.xz) out.xz = dxz;
            if (dy < out.y) { out.y = dy; out.idx = v; }
            if (dxz <= out.xz + 1.0E-9D && dy <= out.y + 1.0E-9D) {
                out.rx = resolved.x; out.ry = resolved.y; out.rz = resolved.z;
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

    private static boolean isFlowingWaterAt(PlayerData data) {
        try {
            int bX = (int) Math.floor(data.getX());
            int bZ = (int) Math.floor(data.getZ());
            int[] ys = {(int) Math.floor(data.getY()), (int) Math.floor(data.getY() + 0.4D)};
            for (int bY : ys) {
                var state = data.getWorldCache().getBlockState(bX, bY, bZ);
                if (state != null && state.getType() != null
                        && state.getType().getName().toUpperCase().contains("WATER")
                        && state.getLevel() > 0) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return true;
        }
        return false;
    }
}
