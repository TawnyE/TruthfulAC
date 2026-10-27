package ret.tawny.truthful.utils.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PositionSanity regression tests: every case mirrors the far-position
 * disabler or a legitimate movement that must never be rejected.
 */
class PositionSanityTest {

    private static final double PREV_X = 100.0, PREV_Y = 64.0, PREV_Z = 100.0;

    private static boolean claim(double x, double y, double z, boolean nearTeleport) {
        return PositionSanity.isImpossibleClaim(x, y, z, PREV_X, PREV_Y, PREV_Z, nearTeleport);
    }

    @Test
    void farPositionPacketWithoutTeleportIsImpossible() {
        // The disabler: a single position packet thousands of blocks away.
        assertTrue(claim(PREV_X + 10_000, PREV_Y, PREV_Z, false), "10k-block jump must be rejected");
        assertTrue(claim(PREV_X, PREV_Y + 10_000, PREV_Z, false), "vertical ghost jumps too");
        assertTrue(claim(PREV_X - 5_000, PREV_Y, PREV_Z - 5_000, false));
    }

    @Test
    void teleportExplainedJumpsAreLegal() {
        // Server teleports (portals, pearls, plugin /tp) land far away but are
        // always visible as pending queue entries.
        assertFalse(claim(PREV_X + 10_000, PREV_Y, PREV_Z, true), "teleport landings must pass");
        assertFalse(claim(-200_000, 128, 3_000_000, true), "even cross-map teleports pass");
    }

    @Test
    void legitimateMovementIsNeverRejected() {
        // Elytra + full-power firework ~4.4 blocks/tick.
        assertFalse(claim(PREV_X + 4.4, PREV_Y - 0.5, PREV_Z, false));
        // TNT-boosted, stacked explosions: still under the vanilla server's
        // own correction threshold, let alone ours.
        assertFalse(claim(PREV_X + 9.9, PREV_Y + 9.9, PREV_Z + 9.9, false));
        // Right AT the threshold boundary, not past it.
        assertFalse(claim(PREV_X + PositionSanity.MAX_JUMP, PREV_Y, PREV_Z, false));
        // One block past: impossible.
        assertTrue(claim(PREV_X + PositionSanity.MAX_JUMP + 1, PREV_Y, PREV_Z, false));
    }

    @Test
    void nonFiniteAndAbsurdCoordinatesAreImpossible() {
        assertTrue(claim(Double.NaN, PREV_Y, PREV_Z, false), "NaN claims must be rejected");
        assertTrue(claim(PREV_X, Double.POSITIVE_INFINITY, PREV_Z, false));
        assertTrue(claim(Double.NEGATIVE_INFINITY, PREV_Y, PREV_Z, false));
        assertTrue(claim(3.5E7, PREV_Y, PREV_Z, false), "beyond the world border");
        assertTrue(claim(PREV_X, 3.5E7, PREV_Z, false));
        // A teleport never explains NaN either - the finite check runs first.
        assertTrue(claim(Double.NaN, PREV_Y, PREV_Z, true));
    }

    @Test
    void teleportTargetVerificationIsTight() {
        // Landing exactly on the target (vanilla behavior).
        assertTrue(PositionSanity.isNearTeleportTarget(100, 64, 100, 100, 64, 100));
        // Within the small landing radius (2-block sphere).
        assertTrue(PositionSanity.isNearTeleportTarget(101.9, 64, 100, 100, 64, 100));
        assertTrue(PositionSanity.isNearTeleportTarget(100, 64, 98.1, 100, 64, 100));
        assertTrue(PositionSanity.isNearTeleportTarget(101.4, 65.0, 100, 100, 64, 100));
        // Just outside it - a withheld confirm cannot launder a 2.1+ claim.
        // A withheld confirm used to launder arbitrary positions; these must
        // now fail verification.
        assertFalse(PositionSanity.isNearTeleportTarget(100 + 2.1, 64, 100, 100, 64, 100));
        assertFalse(PositionSanity.isNearTeleportTarget(100 + 50, 64, 100, 100, 64, 100));
        assertFalse(PositionSanity.isNearTeleportTarget(Double.NaN, 64, 100, 100, 64, 100));
    }
}
