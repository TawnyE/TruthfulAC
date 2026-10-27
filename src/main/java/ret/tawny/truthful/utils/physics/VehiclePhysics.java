package ret.tawny.truthful.utils.physics;

/**
 * Per-vehicle physics in one place so SimD and VectorC can't drift apart.
 *
 * Honestly these numbers are tuned from vanilla feel + watching boats do dumb
 * shit on ice for way too long, not decompiled constants. They're deliberately
 * a bit generous. If a cap here ever causes a false flag, loosen the number,
 * don't add another exemption on top.
 */
public final class VehiclePhysics {

    private VehiclePhysics() {}

    public record Params(
            double friction,
            double steerAccel,
            double maxClimbY,
            double gravity,
            boolean buoyant) {}

    public static Params forType(String typeName, boolean onIce, boolean onBlueIce, boolean inLiquid) {
        String v = typeName != null ? typeName.toUpperCase() : "UNKNOWN";

        if (v.contains("BOAT")) {
            double friction = onBlueIce ? 0.989D : onIce ? 0.98D : 0.95D;
            return new Params(friction, 0.08D, 0.70D, 0.08D, true);
        }
        if (v.contains("CAMEL")) {
            return new Params(0.70D, 0.17D, 1.05D, 0.08D, false);
        }
        if (v.contains("HORSE") || v.contains("DONKEY") || v.contains("MULE")) {
            return new Params(0.70D, 0.14D, 1.05D, 0.08D, false);
        }
        if (v.contains("LLAMA")) {
            return new Params(0.60D, 0.06D, 0.65D, 0.08D, false);
        }
        if (v.contains("PIG")) {
            return new Params(0.60D, 0.06D, 0.52D, 0.08D, false);
        }
        if (v.contains("STRIDER")) {
            return new Params(0.60D, 0.07D, 0.50D, 0.08D, false);
        }
        if (v.contains("MINECART")) {
            // Rails do whatever rails want. Friction high, accel lenient,
            // real enforcement is the absolute cap in the checks.
            return new Params(0.96D, 0.12D, 1.00D, 0.08D, false);
        }
        return new Params(0.60D, 0.08D, 1.05D, 0.08D, false);
    }

    /** Absolute "nope, impossible" speed per type. Last resort bound, not a verdict. */
    public static double absoluteMaxXZ(String typeName, boolean onIce, boolean onBlueIce) {
        String v = typeName != null ? typeName.toUpperCase() : "UNKNOWN";
        if (v.contains("BOAT")) {
            if (onBlueIce) return 9.5D;
            if (onIce) return 5.0D;
            return 1.25D;
        }
        if (v.contains("CAMEL")) return 1.85D;
        if (v.contains("HORSE") || v.contains("DONKEY") || v.contains("MULE")) return 1.45D;
        if (v.contains("LLAMA")) return 0.60D;
        if (v.contains("PIG")) return 0.50D;
        if (v.contains("STRIDER")) return 0.65D;
        if (v.contains("MINECART")) return 8.50D;
        return 1.50D;
    }

    /**
     * Expected horizontal speed this tick: last tick decayed by friction plus
     * whatever the steer input adds. steerMag is 0..1ish from the steer packet.
     */
    public static double predictXZ(double lastDeltaXZ, double steerMag, Params params) {
        double input = Math.max(0.0D, Math.min(1.5D, steerMag)) * params.steerAccel();
        return lastDeltaXZ * params.friction() + input;
    }

    /**
     * Expected vertical speed. Buoyant stuff (boats) eases toward still water,
     * everything else just falls. Jump impulses are handled by the caller via
     * maxClimbY, not here.
     */
    public static double predictY(double lastDeltaY, Params params, boolean inLiquid) {
        if (params.buoyant() && inLiquid) {
            return lastDeltaY * 0.8D;
        }
        return (lastDeltaY - params.gravity()) * 0.98D;
    }
}
