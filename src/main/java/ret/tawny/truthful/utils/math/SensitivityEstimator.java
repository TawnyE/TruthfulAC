package ret.tawny.truthful.utils.math;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public final class SensitivityEstimator {

    private static final int WINDOW_SIZE = 120;
    private static final int MIN_SAMPLES = 8;
    private static final double MIN_DELTA = 0.001D;
    private static final double MAX_DELTA = 45.0D;

    private final ArrayDeque<Double> samples = new ArrayDeque<>(WINDOW_SIZE);
    private SensitivityEstimate estimate = SensitivityEstimate.unresolved();

    public void accept(float delta) {
        if (!Float.isFinite(delta) || delta < MIN_DELTA || delta > MAX_DELTA) {
            return;
        }

        samples.addLast((double) delta);
        if (samples.size() > WINDOW_SIZE) {
            samples.removeFirst();
        }

        if (samples.size() >= MIN_SAMPLES) {
            estimate = estimate(samples);
        }
    }

    public void reset() {
        samples.clear();
        estimate = SensitivityEstimate.unresolved();
    }

    public SensitivityEstimate getEstimate() {
        return estimate;
    }

    public int getSampleCount() {
        return samples.size();
    }

    public static SensitivityEstimate estimate(Collection<Double> rawSamples) {
        if (rawSamples == null || rawSamples.size() < MIN_SAMPLES) {
            return SensitivityEstimate.unresolved();
        }

        List<Double> samples = new ArrayList<>(rawSamples.size());
        for (Double value : rawSamples) {
            if (value != null && Double.isFinite(value) && value >= MIN_DELTA && value <= MAX_DELTA) {
                samples.add(value);
            }
        }
        if (samples.size() < MIN_SAMPLES) {
            return SensitivityEstimate.unresolved();
        }

        Candidate best = null;
        Candidate second = null;
        for (int percent = SensitivityUtil.MIN_PERCENT; percent <= SensitivityUtil.MAX_PERCENT; percent++) {
            Candidate candidate = score(percent, samples);
            if (best == null || candidate.score < best.score) {
                second = best;
                best = candidate;
            } else if (second == null || candidate.score < second.score) {
                second = candidate;
            }
        }

        if (best == null) {
            return SensitivityEstimate.unresolved();
        }

        double fitRatio = best.support / (double) samples.size();
        double separation = second == null ? 1.0D
                : clamp((second.score - best.score) / 0.15D, 0.0D, 1.0D);
        double confidence = clamp(fitRatio * 0.70D + separation * 0.30D, 0.0D, 1.0D);

        boolean resolved = best.score <= 0.22D && fitRatio >= 0.50D && confidence >= 0.35D;
        return new SensitivityEstimate(
                resolved ? best.percent : -1,
                resolved ? best.step : 0.0D,
                samples.size(),
                confidence,
                best.score
        );
    }

    private static Candidate score(int percent, List<Double> samples) {
        double step = SensitivityUtil.getStepForSensitivityPercent(percent);
        List<Double> residuals = new ArrayList<>(samples.size());
        int support = 0;

        for (double delta : samples) {
            long mouseCounts = Math.max(1L, Math.round(delta / step));
            double error = Math.abs(delta - mouseCounts * step);
            double tolerance = Math.max(0.0005D, step * 0.06D);
            double normalized = error / tolerance;
            residuals.add(Math.min(1.0D, normalized));
            if (normalized <= 1.0D) {
                support++;
            }
        }

        residuals.sort(Comparator.naturalOrder());
        double median = percentile(residuals, 0.50D);
        double upperQuartile = percentile(residuals, 0.75D);
        return new Candidate(percent, step, median * 0.40D + upperQuartile * 0.60D, support);
    }

    private static double percentile(List<Double> values, double percentile) {
        int index = (int) Math.round((values.size() - 1) * percentile);
        return values.get(Math.max(0, Math.min(values.size() - 1, index)));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Candidate(int percent, double step, double score, int support) {}

    public record SensitivityEstimate(int percent, double step, int samples,
                                      double confidence, double gridError) {
        public static SensitivityEstimate unresolved() {
            return new SensitivityEstimate(-1, 0.0D, 0, 0.0D, 1.0D);
        }

        public boolean isResolved() {
            return percent >= SensitivityUtil.MIN_PERCENT && percent <= SensitivityUtil.MAX_PERCENT;
        }
    }
}