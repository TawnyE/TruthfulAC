package ret.tawny.truthful.predictionengine.predictions;

import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.predictionengine.PredictionResult;
import ret.tawny.truthful.predictionengine.VanillaMoveMath;
import ret.tawny.truthful.utils.world.Collider;

/**
 * PredictionEngineElytra - glide integration on the carried velocity model.
 *
 * Vanilla (LivingEntity.travel fall-flying branch):
 *   motion = getElytraMovement(start, look)   // lift, dive->speed, re-aim
 *   motion *= (0.99, 0.98, 0.99)              // drag is applied BEFORE collision
 *   move(SELF, motion)                        // collide -> actual delta
 *   carried = collision result
 *
 * Fireworks remain an uncertainty envelope (the server cannot know the
 * remaining burn); the glide itself is now exact and collision-aware.
 */
public final class PredictionEngineElytra {

    private PredictionEngineElytra() {}

    public static PredictionResult emulate(PlayerData data,
                                          double accX, double accZ, double accY,
                                          double lastX, double lastZ, double lastY) {
        final PredictionResult r = new PredictionResult();
        r.offsetXZ = Double.MAX_VALUE;
        r.offsetY = Double.MAX_VALUE;

        final int nowTick = data.getTicksTracked();
        final double gravity = data.getGravity();
        final int levitation = data.getPotionLevel(PotionEffectType.LEVITATION);
        final boolean slowFalling = data.getPotionLevel(PotionEffectType.SLOW_FALLING) > 0;
        final double rawFriction = 0.6D;

        double velX = 0, velY = 0, velZ = 0;
        Vector vel = data.getVelocities().getFreshVelocityVector(1);
        if (vel != null) { velX = vel.getX(); velY = vel.getY(); velZ = vel.getZ(); }

        double[] start = VanillaMoveMath.startVelocity(data, lastX, lastY, lastZ,
                false, rawFriction, gravity, levitation, slowFalling, velX, velY, velZ);

        final float[] yaws = {data.getYaw(), data.getLastYaw()};
        final float[] pitches = {data.getPitch(), data.getLastPitch()};

        final double px = data.getPositionTracker().getLastX();
        final double py = data.getPositionTracker().getLastY();
        final double pz = data.getPositionTracker().getLastZ();
        final double width = Math.max(0.1D, data.getPlayerWidth());
        final double height = Math.max(0.1D, data.getPlayerHeight());
        Collider.Session session = Collider.prepare(data.getWorldCache(), px, py, pz, width, height,
                Math.min(4.0D, Math.abs(start[0]) + 1.0D),
                Math.min(4.0D, Math.abs(start[1]) + 1.0D),
                Math.min(4.0D, Math.abs(start[2]) + 1.0D));
        Collider.Result resolved = new Collider.Result();

        double bestXZ = Double.MAX_VALUE, bestY = Double.MAX_VALUE;
        String bestTag = "glide";
        double brx = 0, bry = 0, brz = 0;

        for (float yaw : yaws) {
            for (float pitch : pitches) {
                double[] glide = elytraMovement(start[0], start[1], start[2], yaw, pitch, data);
                double cx = VanillaMoveMath.clampMinMotion(glide[0] * 0.99D);
                double cy = VanillaMoveMath.clampMinMotion(glide[1] * 0.98D);
                double cz = VanillaMoveMath.clampMinMotion(glide[2] * 0.99D);
                session.resolve(px, py, pz, width, height, cx, cy, cz, false, 0.0D, resolved);
                double dxz = Math.hypot(accX - resolved.x, accZ - resolved.z);
                double dy = Math.abs(accY - resolved.y);
                if (dxz < bestXZ) bestXZ = dxz;
                if (dy < bestY) { bestY = dy; bestTag = "glide"; }
                if (dxz <= bestXZ + 1.0E-9D && dy <= bestY + 1.0E-9D) {
                    brx = resolved.x; bry = resolved.y; brz = resolved.z;
                }
            }
        }

        // Firework envelope: a boosting rocket adds look-aligned impulse every
        // active tick. The remaining burn is unknowable, so this is a
        // containment test, never a point guess.
        int ticksSinceFirework = nowTick - data.getLastFireworkTick();
        if (ticksSinceFirework >= 0 && ticksSinceFirework <= 70) {
            double envelope = 2.0D;
            for (float yaw : yaws) {
                for (float pitch : pitches) {
                    double[] glide = elytraMovement(start[0], start[1], start[2], yaw, pitch, data);
                    double gx = glide[0] * 0.99D, gy = glide[1] * 0.98D, gz = glide[2] * 0.99D;
                    double yawR = Math.toRadians(yaw), pitchR = Math.toRadians(pitch);
                    double lookX = -Math.sin(yawR) * Math.cos(pitchR);
                    double lookY = -Math.sin(pitchR);
                    double lookZ = Math.cos(yawR) * Math.cos(pitchR);
                    double minY = Math.min(gy, gy + lookY * envelope);
                    double maxY = Math.max(gy, gy + lookY * envelope);
                    double dy = Math.max(0.0D, Math.max(minY - accY, accY - maxY));
                    double boostX = gx + lookX * envelope;
                    double boostZ = gz + lookZ * envelope;
                    double dx = Math.max(0.0D, Math.max(Math.min(gx, boostX) - accX, accX - Math.max(gx, boostX)));
                    double dz = Math.max(0.0D, Math.max(Math.min(gz, boostZ) - accZ, accZ - Math.max(gz, boostZ)));
                    if (dy < bestY) bestY = dy;
                    if (Math.hypot(dx, dz) < bestXZ) bestXZ = Math.hypot(dx, dz);
                }
            }
        }

        int slowFall = data.getPotionLevel(PotionEffectType.SLOW_FALLING);
        if (slowFall > 0) bestY = Math.min(bestY, Math.abs(accY - (-0.01D * VanillaMoveMath.VERTICAL_DRAG)));

        // Glide start/stop transitions: the gliding cache and the pose lag the
        // actual flight state by a few ticks. Give a bounded allowance there
        // rather than flagging the transition.
        if (nowTick - data.getLastGlideTick() <= 5 || data.getAirTicks() <= 2) {
            bestXZ = Math.max(0.0D, bestXZ - 0.10D);
            bestY = Math.max(0.0D, bestY - 0.10D);
        }

        // Elytra activation from the ground carries the jump impulse.
        if (data.getAirTicks() <= 2) {
            bestY = Math.min(bestY, Math.abs(accY - 0.42D));
            bestY = Math.min(bestY, Math.abs(accY - 0.38D));
        }

        r.offsetXZ = bestXZ;
        r.offsetY = bestY;
        r.best = bestTag;
        r.predX = brx; r.predY = bry; r.predZ = brz;
        r.carriedX = brx; r.carriedY = bry; r.carriedZ = brz;
        r.offset = Math.hypot(bestXZ, bestY);
        r.hardViolation = r.offset > 1.5D;

        // Elytra drag is already in the candidate; the dispatcher commits the carry.
        r.stateCarried = true;
        return r;
    }

    /**
     * Vanilla getElytraMovement. vector = starting velocity; returns the
     * PRE-drag glide motion for one tick.
     */
    private static double[] elytraMovement(double vx, double vy, double vz,
                                           float yaw, float pitch, PlayerData data) {
        double pitchRad = Math.toRadians(pitch);
        double yawRad = Math.toRadians(yaw);

        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);

        double horizontalSqrt = Math.sqrt(lookX * lookX + lookZ * lookZ);
        double horizontalLength = Math.sqrt(vx * vx + vz * vz);
        double lookLen = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);

        double cosP = Math.cos(pitchRad);
        double vertCos = cosP * cosP * Math.min(1.0D, lookLen / 0.4D);

        int slowFall = data.getPotionLevel(PotionEffectType.SLOW_FALLING);
        double g = data.getGravity();
        if (vy <= 0.0D && slowFall > 0) g = Math.min(g, 0.01D);

        double y = vy + g * (-1.0D + vertCos * 0.75D);
        double x = vx;
        double z = vz;

        if (y < 0.0D && horizontalSqrt > 0.0D) {
            double d5 = y * -0.1D * vertCos;
            x += lookX * d5 / horizontalSqrt;
            y += d5;
            z += lookZ * d5 / horizontalSqrt;
        }

        if (pitchRad < 0.0D && horizontalSqrt > 0.0D) {
            double d = horizontalLength * (-Math.sin(pitchRad)) * 0.04D;
            x += -lookX * d / horizontalSqrt;
            y += d * 3.2D;
            z += -lookZ * d / horizontalSqrt;
        }

        if (horizontalSqrt > 0.0D) {
            x += (lookX / horizontalSqrt * horizontalLength - x) * 0.1D;
            z += (lookZ / horizontalSqrt * horizontalLength - z) * 0.1D;
        }

        return new double[]{x, y, z};
    }
}
