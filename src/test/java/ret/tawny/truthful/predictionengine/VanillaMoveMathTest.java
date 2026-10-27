package ret.tawny.truthful.predictionengine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the input-transform math that replaced the old pairwise-fudge
 * engine. Values are the vanilla client's: 0.98 input scale, unit
 * normalization past length 1 (1.14-1.21.4), unit-square clamp (1.21.5+).
 */
class VanillaMoveMathTest {

    private static final double EPS = 1.0E-6D;

    @Test
    void axisInputScalesBy098() {
        double[] d = VanillaMoveMath.inputDouble(1, 0, false, 0.3D, 0.2D, false);
        assertEquals(0.98D, d[0], EPS);
        assertEquals(0.0D, d[1], EPS);

        double[] m = VanillaMoveMath.inputModern(1, 0, false, 0.3D, 0.2D, false);
        assertEquals(0.98D, m[0], EPS);
        assertEquals(0.0D, m[1], EPS);
    }

    @Test
    void diagonalNormalizesToUnitDiagonal() {
        double inv = 1.0D / Math.sqrt(2.0D);
        double[] d = VanillaMoveMath.inputDouble(1, 1, false, 0.3D, 0.2D, false);
        assertEquals(inv, d[0], 1.0E-4D);
        assertEquals(inv, d[1], 1.0E-4D);

        double[] m = VanillaMoveMath.inputModern(1, 1, false, 0.3D, 0.2D, false);
        assertEquals(inv, m[0], 1.0E-4D);
        assertEquals(inv, m[1], 1.0E-4D);
    }

    @Test
    void zeroInputStaysZero() {
        double[] d = VanillaMoveMath.inputDouble(0, 0, false, 0.3D, 0.2D, false);
        assertEquals(0.0D, d[0], EPS);
        assertEquals(0.0D, d[1], EPS);
        double[] m = VanillaMoveMath.inputModern(0, 0, false, 0.3D, 0.2D, false);
        assertEquals(0.0D, m[0], EPS);
        assertEquals(0.0D, m[1], EPS);
    }

    @Test
    void sneakScalesInput() {
        double[] d = VanillaMoveMath.inputDouble(1, 0, true, 0.3D, 0.2D, false);
        // 1 * 0.3 (sneak) then * 0.98
        assertEquals(0.3D * 0.98D, d[0], EPS);
    }

    @Test
    void usingItemScalesInput() {
        double[] d = VanillaMoveMath.inputDouble(1, 0, false, 0.3D, 0.2D, true);
        // 1 * 0.2 (item use) then * 0.98
        assertEquals(0.2D * 0.98D, d[0], EPS);
    }

    @Test
    void minMotionClamp() {
        assertEquals(0.0D, VanillaMoveMath.clampMinMotion(0.0029D), EPS);
        assertEquals(0.0D, VanillaMoveMath.clampMinMotion(-0.0029D), EPS);
        assertEquals(0.004D, VanillaMoveMath.clampMinMotion(0.004D), EPS);
    }

    @Test
    void modernClampKeepsMagnitudeWithinUnitSquare() {
        double[] m = VanillaMoveMath.inputModern(1, 0.5D, false, 0.3D, 0.2D, false);
        assertTrue(Math.abs(m[0]) <= 1.0D + EPS);
        assertTrue(Math.abs(m[1]) <= 1.0D + EPS);
        // Still points along the same normalized direction.
        assertTrue(m[0] > 0 && m[1] > 0);
    }

    @Test
    void fluidSettleHitsTheSurfaceQuirk() {
        // With a non-default gravity step the window is reachable:
        // |y - 0.005| >= 0.003 and |y - gStep| < 0.003 -> -0.003.
        double gStep = 0.006D;
        double y = 0.0085D; // 0.0035 from 0.005, 0.0025 from gStep
        assertEquals(-0.003D, VanillaMoveMath.fluidSettle(y, gStep, true), EPS);
        // Not falling -> normal gravity step
        assertEquals(y - gStep, VanillaMoveMath.fluidSettle(y, gStep, false), EPS);
        // Outside the window -> normal gravity step
        assertEquals(-0.5D - gStep, VanillaMoveMath.fluidSettle(-0.5D, gStep, true), EPS);
    }
}
