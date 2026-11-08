package com.tradingstrat.indicator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Support/resistance levels discovered from swing pivots.
 *
 * Pivot highs and lows are found with {@link Ta#pivotHigh}/{@link Ta#pivotLow}, then
 * pivots at similar prices are clustered into a single level. A level's strength is
 * how many pivots landed in it — a price the market turned at four times is a stronger
 * level than one it turned at once.
 *
 * Highs and lows are pooled into one set of levels rather than kept separate, because
 * broken resistance commonly acts as support afterwards (and vice versa). Whether a
 * level is "support" or "resistance" is therefore a question of where price is now,
 * answered by {@link Result#nearestBelow} and {@link Result#nearestAbove}.
 *
 * This is an indicator: it reports levels and makes no trade decision.
 */
public final class SupportResistance {

    private SupportResistance() {}

    /**
     * @param lookbackBars        how far back to collect pivots
     * @param pivotLeft           bars required to the left of a pivot
     * @param pivotRight          bars required to the right (also the confirmation lag)
     * @param clusterTolerancePct pivots within this % of a cluster's mean join it
     * @param minTouches          pivots a cluster needs before it counts as a level
     */
    public record Params(
            int lookbackBars,
            int pivotLeft,
            int pivotRight,
            double clusterTolerancePct,
            int minTouches
    ) {
        public static Params defaults() {
            return new Params(200, 2, 2, 0.35, 2);
        }
    }

    /**
     * A price level.
     *
     * @param price        mean price of the pivots in the cluster
     * @param touches      how many pivots formed it
     * @param lastTouchBar index of the most recent pivot in it
     */
    public record Level(double price, int touches, int lastTouchBar) {}

    /** Levels ordered by price, ascending. */
    public record Result(List<Level> levels) {

        /** Strongest level strictly below {@code price}, preferring the nearest. */
        public Optional<Level> nearestBelow(double price) {
            return levels.stream()
                    .filter(l -> l.price() < price)
                    .max(Comparator.comparingDouble(Level::price));
        }

        /** Strongest level strictly above {@code price}, preferring the nearest. */
        public Optional<Level> nearestAbove(double price) {
            return levels.stream()
                    .filter(l -> l.price() > price)
                    .min(Comparator.comparingDouble(Level::price));
        }
    }

    /** Compute with the default parameters. */
    public static Result compute(double[] high, double[] low) {
        return compute(high, low, Params.defaults());
    }

    /**
     * @param high candle highs, oldest → newest
     * @param low  candle lows, index-aligned with {@code high}
     */
    public static Result compute(double[] high, double[] low, Params p) {
        int n = high.length;
        int from = Math.max(0, n - p.lookbackBars());

        double[] pivotHighs = Ta.pivotHigh(high, p.pivotLeft(), p.pivotRight());
        double[] pivotLows = Ta.pivotLow(low, p.pivotLeft(), p.pivotRight());

        // Collect (price, bar) for every confirmed pivot in the lookback.
        List<double[]> pivots = new ArrayList<>();
        for (int i = from; i < n; i++) {
            if (!Double.isNaN(pivotHighs[i])) {
                pivots.add(new double[]{pivotHighs[i], i});
            }
            if (!Double.isNaN(pivotLows[i])) {
                pivots.add(new double[]{pivotLows[i], i});
            }
        }
        if (pivots.isEmpty()) {
            return new Result(List.of());
        }

        pivots.sort(Comparator.comparingDouble(a -> a[0]));

        // Greedy clustering: walk prices upward, extending the current cluster while the
        // next pivot sits within tolerance of the cluster's running mean.
        List<Level> levels = new ArrayList<>();
        List<double[]> current = new ArrayList<>();
        current.add(pivots.get(0));
        double mean = pivots.get(0)[0];

        for (int i = 1; i < pivots.size(); i++) {
            double[] pivot = pivots.get(i);
            double tolerance = Math.abs(mean) * p.clusterTolerancePct() / 100.0;
            if (pivot[0] - mean <= tolerance) {
                current.add(pivot);
                mean = average(current);
            } else {
                addLevel(levels, current, p.minTouches());
                current = new ArrayList<>();
                current.add(pivot);
                mean = pivot[0];
            }
        }
        addLevel(levels, current, p.minTouches());

        levels.sort(Comparator.comparingDouble(Level::price));
        return new Result(levels);
    }

    private static void addLevel(List<Level> levels, List<double[]> cluster, int minTouches) {
        if (cluster.size() < minTouches) {
            return;
        }
        int lastBar = 0;
        for (double[] pivot : cluster) {
            lastBar = Math.max(lastBar, (int) pivot[1]);
        }
        levels.add(new Level(average(cluster), cluster.size(), lastBar));
    }

    private static double average(List<double[]> cluster) {
        double sum = 0;
        for (double[] pivot : cluster) {
            sum += pivot[0];
        }
        return sum / cluster.size();
    }
}
