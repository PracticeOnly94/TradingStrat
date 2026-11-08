package com.tradingstrat.ta4j;

import com.tradingstrat.model.ChartData;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.supportresistance.VolumeProfileKDEIndicator;
import org.ta4j.core.num.Num;

/**
 * Volume-at-price, backed by ta4j's {@link VolumeProfileKDEIndicator}.
 *
 * <p>Where {@link com.tradingstrat.indicator.SupportResistance} asks <em>where did price
 * turn</em>, this asks <em>where did price trade</em>. The point of carrying both is that
 * volume is genuinely new information: WaveTrend, RSI, Stoch RSI and the money-flow
 * ribbon are all functions of recent price change, so they tend to agree with each other.
 * A volume-derived level does not.
 *
 * <p>The headline output is the <b>point of control</b> (POC) — the most traded price in
 * the window, which tends to act as a magnet rather than a barrier. Treat it as context,
 * not as a stop level: a KDE mode sits where price spent time, which in a clean trading
 * range is the middle, not the edges.
 */
public final class VolumeProfile {

    private VolumeProfile() {}

    /**
     * @param poc          most traded price in the window (the KDE mode)
     * @param densityAtPoc kernel density at the POC
     * @param lastClose    close of the final bar, for orientation
     */
    public record Result(double poc, double densityAtPoc, double lastClose) {

        /** True when price is trading above the most-traded price. */
        public boolean priceAbovePoc() {
            return lastClose > poc;
        }

        /** Distance from price to the POC, as a percentage of price. */
        public double distanceToPocPct() {
            return lastClose == 0 ? 0 : (lastClose - poc) / lastClose * 100.0;
        }
    }

    /**
     * Bandwidth as a fraction of price: the KDE smoothing width. Too small and every
     * bar becomes its own peak; too large and the profile flattens into one blob.
     */
    private static final double DEFAULT_BANDWIDTH_PCT = 0.25;

    /** Compute over the whole series with the default bandwidth. */
    public static Result compute(ChartData chart) {
        return compute(chart, DEFAULT_BANDWIDTH_PCT);
    }

    public static Result compute(ChartData chart, double bandwidthPct) {
        BarSeries series = BarSeriesAdapter.toBarSeries(chart);
        int last = series.getBarCount() - 1;
        if (last < 0) {
            return new Result(Double.NaN, Double.NaN, Double.NaN);
        }

        double lastClose = series.getBar(last).getClosePrice().doubleValue();
        // ta4j takes an absolute bandwidth; scale it off price so it travels across symbols.
        Num bandwidth = series.numFactory().numOf(Math.abs(lastClose) * bandwidthPct / 100.0);

        VolumeProfileKDEIndicator kde = new VolumeProfileKDEIndicator(series, bandwidth);
        Num poc = kde.getModePrice(last);
        if (poc == null || poc.isNaN()) {
            return new Result(Double.NaN, Double.NaN, lastClose);
        }

        return new Result(
                poc.doubleValue(),
                kde.getDensityAtPrice(last, poc).doubleValue(),
                lastClose);
    }
}
