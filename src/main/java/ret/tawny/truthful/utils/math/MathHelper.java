package ret.tawny.truthful.utils.math;

import ret.tawny.truthful.utils.vec.Vector2f;

public final class MathHelper {

    public static final float RADIAN = (float) (Math.PI / 180.0F);
    private static final long EXPANDER = 16777216L; // 2^24

    /**
     * Wraps an angle to the range -180 to 180.
     */
    public static float wrapAngleTo180_float(float value) {
        value = value % 360.0F;
        if (value >= 180.0F)
            value -= 360.0F;

        if (value < -180.0F)
            value += 360.0F;
        return value;
    }

    /**
     * Recursive GCD with a noise floor cutoff.
     * Used specifically for sensitivity analysis where the 'remainder' might not be
     * 0 due to float errors.
     */
    public static long getGcd(final long current, final long previous) {
        return (previous <= 16384L) ? current : getGcd(previous, current % previous);
    }

    /**
     * Standard Greatest Common Divisor.
     */
    public static long gcd(long a, long b) {
        return (b == 0) ? a : gcd(b, a % b);
    }

    /**
     * Helper to calculate GCD for floating point numbers using an expansion
     * multiplier.
     * 
     * @param current  The current delta.
     * @param previous The previous delta.
     * @return The estimated common divisor (sensitivity step).
     */
    public static double getGcd(final double current, final double previous) {
        long a = (long) (current * EXPANDER);
        long b = (long) (previous * EXPANDER);
        return (double) getGcd(a, b) / EXPANDER;
    }

    public static double pow(final double base, final double exponent) {
        double result = 1;
        if (exponent < 0)
            return result / pow(base, -exponent);

        for (int i = 0; i < exponent; ++i)
            result *= base;

        return result;
    }

    public static float pow(final float base, final float exponent) {
        float result = 1;
        if (exponent < 0)
            return result / pow(base, -exponent);

        for (int i = 0; i < exponent; ++i)
            result *= base;

        return result;
    }

    public static double[] distributeByWeight(final double base, final double... values) {
        final int size = values.length;
        final double[] weighted = new double[size];

        double sum = 0;
        for (final double d : values)
            sum += d;
        for (int i = 0; i < size; ++i)
            weighted[i] = base * (values[i] / (sum * 100.0F));
        return weighted;
    }

    public static float lerp(final float start, final float end, final float t) {
        return start * (1 - t) + end * t;
    }

    public static Vector2f quadraticBezier(final Vector2f p0, final Vector2f p1, final Vector2f p2, final float t) {
        if (t < 0 || t > 1)
            throw new IllegalArgumentException("t must be between 0 and 1");
        final float x = pow(1 - t, 2) * p0.getX() + (1 - t) * 2 * t * p1.getX() + t * t * p2.getX();
        final float y = pow(1 - t, 2) * p0.getY() + (1 - t) * 2 * t * p1.getY() + t * t * p2.getY();
        return new Vector2f(x, y);
    }

    public static float quadraticBezierPoint(final float p0, final float p1, final float p2, final float t) {
        if (t < 0 || t > 1)
            throw new IllegalArgumentException("t must be between 0 and 1");
        return (float) (pow(1 - t, 2) * p0 + (1 - t) * 2 * t * p1 + t * t * p2);
    }

    public static Vector2f dynamicBezier(final float t, final Vector2f... ctrlPoints) {
        final int size = ctrlPoints.length;
        if (size < 2)
            throw new IllegalArgumentException("A bezier curve requires at least 2 control points");
        if (t < 0 || t > 1)
            throw new IllegalArgumentException("t must be between 0 and 1");

        float x = 0;
        float y = 0;

        int n = size - 1;
        for (int i = 0; i <= n; i++) {
            final double b = binomialCoefficient(n, i) * Math.pow(1 - t, n - i) * Math.pow(t, i);
            x += b * ctrlPoints[i].getX();
            y += b * ctrlPoints[i].getY();
        }
        return new Vector2f(x, y);
    }

    public static double binomialCoefficient(final int n, final int k) {
        double c = 1;
        for (int i = 0; i < k; ++i)
            c = c * (n - i) / (i + 1);
        return c;
    }

    public static double getMean(java.util.List<Integer> values) {
        if (values.isEmpty())
            return 0.0;
        double sum = 0.0;
        for (int val : values) {
            sum += val;
        }
        return sum / values.size();
    }

    public static double getVariance(java.util.List<Integer> values) {
        if (values.isEmpty())
            return 0.0;
        double mean = getMean(values);
        double temp = 0;
        for (int val : values) {
            temp += (val - mean) * (val - mean);
        }
        return temp / values.size();
    }

    public static double getSkewness(java.util.List<Integer> values) {
        if (values.isEmpty())
            return 0.0;
        double mean = getMean(values);
        double variance = getVariance(values);
        double stdDev = Math.sqrt(variance);

        if (stdDev == 0)
            return 0.0;

        double sum = 0.0;
        for (int val : values) {
            sum += Math.pow(val - mean, 3);
        }
        return sum / (values.size() * Math.pow(stdDev, 3));
    }

    public static double getKurtosis(java.util.List<Integer> values) {
        if (values.isEmpty())
            return 0.0;
        double mean = getMean(values);
        double variance = getVariance(values);
        double stdDev = Math.sqrt(variance);

        if (stdDev == 0)
            return 0.0;

        double sum = 0.0;
        for (int val : values) {
            sum += Math.pow(val - mean, 4);
        }
        // Excess Kurtosis = (Moment4 / StdDev^4) - 3
        return (sum / (values.size() * Math.pow(stdDev, 4))) - 3.0;
    }
}