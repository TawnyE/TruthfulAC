package ret.tawny.truthful.predictionengine.predictions;

import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.predictionengine.PredictionResult;
import ret.tawny.truthful.predictionengine.PredictionState;
import ret.tawny.truthful.utils.physics.VehiclePhysics;
import ret.tawny.truthful.utils.world.WorldUtils;

/**
 * PredictionEngineVehicle - ridden-entity motion.
 *
 * Boats now run the vanilla control/float model on carried momentum, along
 * the BOAT's yaw (tracked from SET_PASSENGERS + the vehicle's rotation
 * packets), with the real steer input:
 *   floatBoat : x,z *= invFriction (0.9 water / landFriction / 0.9 air /
 *               0.45 submerged); y += -0.04; buoyancy (y + d2*0.061538)
 *               *0.75 while in water.
 *   controlBoat: +0.04 forward (>0.1), -0.005 back (<-0.01), +0.005 strafe,
 *               applied along the boat yaw.
 * Mounts/others keep a per-type momentum model with a hard absolute cap.
 *
 * The rider's reported delta equals the vehicle's delta, so the carried
 * velocity and the model both apply to the vehicle.
 */
public final class PredictionEngineVehicle {

    private PredictionEngineVehicle() {}

    // Boats/vehicles are notoriously glitchy (server-side lerping, water
    // bobbing, land friction sampling, rider mass). Grim itself gives them
    // huge allowances; a tight model here only produces false flags.
    private static final double BASE_ALLOWANCE_XZ = 0.15D;
    private static final double BASE_ALLOWANCE_Y = 0.20D;
    private static final double BOAT_HEIGHT = 0.5625D;

    public static PredictionResult evaluate(PlayerData data,
                                           double accXZ, double lastXZ,
                                           double accY, double lastY,
                                           boolean inLiquid, boolean onIce, boolean onBlueIce) {
        PredictionResult r = new PredictionResult();
        r.offsetXZ = Double.MAX_VALUE;
        r.offsetY = Double.MAX_VALUE;

        final String type;
        try {
            type = data.getVehicleTypeName().isEmpty() ? "UNKNOWN" : data.getVehicleTypeName();
        } catch (Throwable t) {
            return PredictionResult.unsupported();
        }
        final boolean isBoat = type.contains("BOAT") || type.contains("RAFT");
        final int nowTick = data.getTicksTracked();

        // carried vehicle momentum
        PredictionState state = data.getPredictionState();
        double baseX, baseY, baseZ;
        if (state.isContiguous(nowTick)) {
            baseX = state.velX;
            baseY = state.velY;
            baseZ = state.velZ;
        } else {
            baseX = data.getLastDeltaX();
            baseY = data.getLastDeltaY();
            baseZ = data.getLastDeltaZ();
        }

        if (isBoat) {
            evaluateBoat(data, r, baseX, baseY, baseZ, accXZ, accY, inLiquid, onIce, onBlueIce, type);
        } else {
            evaluateMount(data, r, lastXZ, accXZ, accY, lastY, inLiquid, onIce, onBlueIce, type);
        }

        r.offset = Math.hypot(r.offsetXZ, r.offsetY);
        if (!r.hardViolation && r.offset > 0.75D) r.hardViolation = true;
        return r;
    }

    private static void evaluateBoat(PlayerData data, PredictionResult r,
                                     double baseX, double baseY, double baseZ,
                                     double accXZ, double accY,
                                     boolean inLiquid, boolean onIce, boolean onBlueIce,
                                     String type) {
        final boolean freshSteer = data.getTicksTracked() - data.getActionTracker().getVehicleSteerTick() <= 2;
        final float forward = freshSteer ? data.getActionTracker().getVehicleForward() : 0.0F;
        final float sideways = freshSteer ? data.getActionTracker().getVehicleSideways() : 0.0F;

        final double fluidHeight = WorldUtils.fluidHeightAt(data, false);
        final boolean inWater = inLiquid || fluidHeight > 0.0D;
        final boolean submerged = inWater && fluidHeight >= BOAT_HEIGHT;
        final double waterLevel = Math.floor(data.getY()) + fluidHeight;

        // ---- floatBoat friction / buoyancy ----
        double invFriction;
        double d1 = -0.04D;
        double d2 = 0.0D;
        if (inWater) {
            if (submerged) {
                invFriction = 0.45D;
                d2 = 0.01D;
            } else {
                invFriction = 0.9D;
                d2 = (waterLevel - data.getY()) / BOAT_HEIGHT;
                if (d2 < 0.0D) d2 = 0.0D;
            }
        } else if (data.isServerGround()) {
            invFriction = Math.max(0.0D, ret.tawny.truthful.checks.impl.movement.MovementCheckSupport
                    .computeGroundFrictionRaw(data));
        } else {
            invFriction = 0.9D;
        }

        double fx = baseX * invFriction;
        double fz = baseZ * invFriction;
        double fy = baseY + d1;
        if (d2 > 0.0D) {
            fy = (fy + d2 * 0.06153846016296973D) * 0.75D;
        }

        // ---- controlBoat along the BOAT's yaw ----
        final double yaw = Math.toRadians(data.getVehicleYaw());
        final double sin = Math.sin(yaw), cos = Math.cos(yaw);

        // Offer the standard control and the "intermediate" (forward==0) pass.
        double[][] steers = controlVariants(forward, sideways);

        double bestXZ = Double.MAX_VALUE;
        double bestY = Math.abs(accY - fy);
        String bestTag = submerged ? "boat_sub" : (inWater ? "boat_water" : "boat_land");
        double brx = fx, bry = fy, brz = fz;

        for (double[] f : steers) {
            double cx = fx + (-sin * f[0]);
            double cz = fz + (cos * f[0]);
            double dxz = Math.abs(accXZ - Math.hypot(cx, cz));
            if (dxz < bestXZ) {
                bestXZ = dxz;
                brx = cx; brz = cz; bry = fy;
                bestTag = submerged ? "boat_sub" : (inWater ? "boat_water" : "boat_land");
            }
        }

        r.offsetXZ = Math.max(0.0D, bestXZ - BASE_ALLOWANCE_XZ);
        r.offsetY = Math.max(0.0D, bestY - BASE_ALLOWANCE_Y);
        r.best = bestTag;
        r.predX = brx; r.predY = bry; r.predZ = brz;
        r.carriedX = brx; r.carriedY = bry; r.carriedZ = brz;
        r.stateCarried = true;

        // absolute backstop
        double absMax = VehiclePhysics.absoluteMaxXZ(type, onIce, onBlueIce);
        if (accXZ > absMax) {
            double over = accXZ - absMax;
            if (over > r.offsetXZ) { r.offsetXZ = over; r.best = "boat_abs_cap"; }
            r.hardViolation = true;
        }
    }

    private static double[][] controlVariants(float forward, float sideways) {
        java.util.ArrayList<double[]> list = new java.util.ArrayList<>(3);
        list.add(new double[]{controlF(forward, sideways)});
        list.add(new double[]{0.0D});
        if (forward == 0.0F && sideways != 0.0F) {
            // intermediate pass (boat still paddles when only strafing)
            list.add(new double[]{0.045D});
        }
        return list.toArray(new double[0][]);
    }

    private static double controlF(float forward, float sideways) {
        double f = 0.0D;
        if (sideways != 0.0F && forward == 0.0F) f += 0.005D;
        if (forward > 0.1F) f += 0.04D;
        if (forward < -0.01F) f -= 0.005D;
        return f;
    }

    private static void evaluateMount(PlayerData data, PredictionResult r,
                                      double lastXZ, double accXZ, double accY, double lastY,
                                      boolean inLiquid, boolean onIce, boolean onBlueIce,
                                      String type) {
        VehiclePhysics.Params params = VehiclePhysics.forType(type, onIce, onBlueIce, inLiquid);
        boolean freshSteer = data.getTicksTracked() - data.getActionTracker().getVehicleSteerTick() <= 2;
        double steerMag = freshSteer ? Math.abs(data.getActionTracker().getVehicleForward()) : 0.0D;

        double predUp = lastXZ * params.friction() + steerMag * params.steerAccel();
        double predHold = lastXZ * params.friction();

        double bestXZ = Math.min(Math.abs(accXZ - predUp), Math.abs(accXZ - predHold));
        double predY = VehiclePhysics.predictY(lastY, params, inLiquid);
        double bestY = Math.abs(accY - predY);
        bestY = Math.min(bestY, Math.abs(accY - params.maxClimbY() * 0.9D));

        r.offsetXZ = Math.max(0.0D, bestXZ - BASE_ALLOWANCE_XZ);
        r.offsetY = Math.max(0.0D, bestY - BASE_ALLOWANCE_Y);
        r.best = "vehicle_" + type.toLowerCase();
        r.carriedX = data.getLastDeltaX();
        r.carriedY = predY;
        r.carriedZ = data.getLastDeltaZ();
        r.stateCarried = true;

        double absMax = VehiclePhysics.absoluteMaxXZ(type, onIce, onBlueIce);
        if (accXZ > absMax) {
            double over = accXZ - absMax;
            if (over > r.offsetXZ) { r.offsetXZ = over; r.best = "vehicle_abs_cap"; }
            r.hardViolation = true;
        }
    }
}
