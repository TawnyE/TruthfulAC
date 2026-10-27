package ret.tawny.truthful.predictionengine;

import org.bukkit.util.Vector;
import ret.tawny.truthful.data.PlayerData;
import ret.tawny.truthful.predictionengine.predictions.PredictionEngineElytra;
import ret.tawny.truthful.predictionengine.predictions.PredictionEngineLiquid;
import ret.tawny.truthful.predictionengine.predictions.PredictionEngineLegs;
import ret.tawny.truthful.predictionengine.predictions.PredictionEngineVehicle;
import ret.tawny.truthful.utils.world.PhysicsConstants;
import ret.tawny.truthful.utils.world.WorldUtils;

/**
 * PredictionEngine - regime dispatcher: one entry point that routes each
 * movement tick to the matching physics variant:
 *   - PredictionEngineVehicle  : ridden entities
 *   - PredictionEngineElytra   : gliding
 *   - PredictionEngineLiquid   : water & lava
 *   - PredictionEngineLegs     : walking / sprinting / jumping / falling
 *
 * Every state-aware engine reports the velocity to carry; the dispatcher
 * commits exactly one (the winning candidate's) so the merge paths cannot
 * double-write it. Vehicles reset the carry (their motion is not the
 * rider's), so a dismount reconstructs cleanly.
 *
 * Engine architecture and the vanilla-physics modelling approach are
 * inspired by and credited to Grim Anti-Cheat
 * (https://github.com/GrimAnticheat/Grim). All code in this package is an
 * independent implementation.
 */
public final class PredictionEngine {

    private PredictionEngine() {}

    public static PredictionResult simulate(PlayerData data) {
        final double accX = data.getDeltaX(), accY = data.getDeltaY(), accZ = data.getDeltaZ();
        final double lastX = zeroMinMotion(data.getLastDeltaX());
        final double lastZ = zeroMinMotion(data.getLastDeltaZ());
        final double lastY = data.getLastDeltaY();

        double velX = 0, velY = 0, velZ = 0;
        // Fresh-only: KB is applied by the client once (the tick it arrives)
        // and then lives inside the reported motion - re-adding the decayed
        // entry every tick double-counts it (this was the Evoker-KB false).
        Vector vel = data.getVelocities().getFreshVelocityVector(1);
        if (vel != null) { velX = vel.getX(); velY = vel.getY(); velZ = vel.getZ(); }

        final boolean onGround = data.isServerGround();
        final boolean wasOnGround = data.isLastGround();
        final int airTicks = data.getAirTicks();

        final PredictionResult result;

        if (data.isInsideVehicle()) {
            boolean inLiquid = data.isInLiquid();
            boolean onBlueIce = WorldUtils.isNearBlueIce(data);
            boolean onIce = onBlueIce || WorldUtils.isNearIce(data);
            result = PredictionEngineVehicle.evaluate(data, data.getDeltaXZ(), data.getLastDeltaXZ(),
                    accY, lastY, inLiquid, onIce, onBlueIce);
        } else if (data.isGliding() && !onGround) {
            result = PredictionEngineElytra.emulate(data, accX, accZ, accY, lastX, lastZ, lastY);
        } else if (data.getTicksTracked() - data.getLastGlideTick() <= 40) {
            // Post-glide ticks run BOTH engines; the gliding cache lags at
            // glide start/end and hop bounces, so the best candidate wins.
            PredictionResult elytra = PredictionEngineElytra.emulate(data, accX, accZ, accY, lastX, lastZ, lastY);
            PredictionResult normal = PredictionEngineLegs.emulate(data, accX, accZ, accY, lastX, lastZ, lastY,
                    onGround, wasOnGround, airTicks,
                    onGround ? ret.tawny.truthful.checks.impl.movement.MovementCheckSupport.computeGroundFriction(data) : 0.91D,
                    velX, velY, velZ);
            PredictionResult merged = new PredictionResult();
            merged.offsetXZ = Math.min(elytra.offsetXZ, normal.offsetXZ);
            merged.offsetY = Math.min(elytra.offsetY, normal.offsetY);
            merged.offset = Math.hypot(merged.offsetXZ, merged.offsetY);
            merged.best = elytra.offset <= normal.offset ? elytra.best : normal.best;
            merged.hardViolation = elytra.hardViolation && normal.hardViolation;
            merged.slowMult = normal.slowMult;
            merged.sprintBackwards = normal.sprintBackwards;
            merged.knownInput = normal.knownInput;
            boolean elytraWins = elytra.offset <= normal.offset;
            merged.carriedX = elytraWins ? elytra.carriedX : normal.carriedX;
            merged.carriedY = elytraWins ? elytra.carriedY : normal.carriedY;
            merged.carriedZ = elytraWins ? elytra.carriedZ : normal.carriedZ;
            merged.stateCarried = elytra.stateCarried || normal.stateCarried;
            result = merged;
        } else if (data.isInLiquid()) {
            boolean inLava = WorldUtils.isNearLiquid(data)
                    && ret.tawny.truthful.utils.world.BlockPropertyRegistry.getName(
                            data.getWorldCache().getBlockId(
                                    (int) Math.floor(data.getX()),
                                    (int) Math.floor(data.getY()),
                                    (int) Math.floor(data.getZ()))).contains("LAVA");
            result = PredictionEngineLiquid.emulate(data, accX, accZ, accY, lastX, lastZ, lastY, onGround, inLava);
        } else {
            double friction = onGround
                    ? ret.tawny.truthful.checks.impl.movement.MovementCheckSupport.computeGroundFriction(data)
                    : 0.91D;
            result = PredictionEngineLegs.emulate(data, accX, accZ, accY, lastX, lastZ, lastY,
                    onGround, wasOnGround, airTicks, friction, velX, velY, velZ);
        }

        // Commit exactly one carried velocity.
        PredictionState state = data.getPredictionState();
        if (result.stateCarried) {
            state.carry(result.carriedX, result.carriedY, result.carriedZ, data.getTicksTracked());
        } else {
            state.reset();
        }
        return result;
    }

    private static double zeroMinMotion(double v) {
        return Math.abs(v) < PhysicsConstants.MIN_MOTION ? 0.0D : v;
    }
}
