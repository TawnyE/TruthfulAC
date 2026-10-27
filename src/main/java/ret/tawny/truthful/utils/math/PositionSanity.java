package ret.tawny.truthful.utils.math;

/**
 * PositionSanity - protocol-level plausibility for client position claims.
 *
 * A vanilla client can move at most ~10 blocks between movement packets
 * (the server's own "moved too quickly" correction threshold); every legal
 * larger jump comes from a server teleport, and teleports are always
 * visible to us as pending queue entries (the server sends the client a
 * position-and-look for every teleport, including plugin, portal, pearl,
 * chorus, respawn and lagback teleports).
 *
 * So a position claim that is neither finite, nor within the coordinate
 * space, nor within MAX_JUMP of the last accepted position, nor near ANY
 * pending teleport target, is IMPOSSIBLE traffic: no legitimate client can
 * produce it. That claim is the fuel of the "far position packet" disabler -
 * feeding it to the server forces a correction teleport per packet, and the
 * correction churn (exemptions, tracker resets, timer rebasing) is what
 * blinds the anti-cheat. Such packets are dropped at the door instead: the
 * server never sees them, so it never builds corrections around them.
 */
public final class PositionSanity {

    private PositionSanity() {}

    /**
     * 32 blocks per movement packet. Three times the vanilla server's own
     * "moved too quickly" threshold and far above any legit mechanic
     * (elytra+fireworks ~4.4/tick, TNT-boosted ~4/tick, boats on blue ice
     * ~0.8/tick). Anything larger was always about to be corrected by the
     * server itself.
     */
    public static final double MAX_JUMP = 32.0D;
    public static final double MAX_JUMP_SQ = MAX_JUMP * MAX_JUMP;

    /** Vanilla's world border lives at 29,999,984; anything past is junk. */
    public static final double MAX_COORDINATE = 2.9E7D;

    /** Pending-teleport proximity: clients land exactly on the target. */
    public static final double TELEPORT_MATCH_RADIUS_SQ = 4.0D;

    /**
     * True when the claim is impossible and must never reach the server or
     * the movement state.
     *
     * @param x/y/z the claimed position
     * @param prevX/Y/Z the last ACCEPTED position (server-plausible anchor)
     * @param nearPendingTeleport whether any pending teleport target is near
     */
    public static boolean isImpossibleClaim(double x, double y, double z,
                                            double prevX, double prevY, double prevZ,
                                            boolean nearPendingTeleport) {
        if (!isFinite(x) || !isFinite(y) || !isFinite(z)) return true;
        if (Math.abs(x) > MAX_COORDINATE || Math.abs(y) > MAX_COORDINATE || Math.abs(z) > MAX_COORDINATE) {
            return true;
        }
        if (nearPendingTeleport) return false;
        double dx = x - prevX;
        double dy = y - prevY;
        double dz = z - prevZ;
        return dx * dx + dy * dy + dz * dz > MAX_JUMP_SQ;
    }

    /**
     * True when a teleport-confirmed position claim actually lands on (or
     * within a small radius of) the teleport target. Vanilla clients accept
     * the teleport and report its exact position on the next packet; a cheat
     * that confirms a teleport and then claims an unrelated position is
     * harvesting the confirm to launder a teleport cheat.
     */
    public static boolean isNearTeleportTarget(double x, double y, double z,
                                               double targetX, double targetY, double targetZ) {
        double dx = x - targetX;
        double dy = y - targetY;
        double dz = z - targetZ;
        return dx * dx + dy * dy + dz * dz <= TELEPORT_MATCH_RADIUS_SQ;
    }

    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }
}
