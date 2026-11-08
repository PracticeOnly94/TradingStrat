package com.tradingstrat.analysis;

import java.util.List;

/**
 * Forward returns by condition, against the all-bars baseline.
 *
 * <p>Read the {@code edge} columns rather than the raw means: crypto drifts, so a
 * condition returning +0.3% over two days is worthless if an average bar returns +0.3%
 * too. The edge is what the condition adds over simply being in the market.
 */
public record ConditionReport(
        String interval,
        int symbols,
        int bars,
        int[] horizons,
        List<ConditionStat> stats,
        List<String> failures
) {

    /**
     * @param occurrences   bars where the condition held
     * @param frequencyPct  share of all bars
     * @param mean12/24/48  mean forward return, in percent
     * @param edge48        mean 48-bar return minus the baseline's, in percent
     * @param hit48         share of occurrences with a positive 48-bar return
     * @param atr48         mean 48-bar return in ATRs, which normalises for volatility
     * @param effectiveN    occurrences divided by the horizon: forward windows on
     *                      adjacent bars overlap almost entirely, so the independent
     *                      sample is far smaller than the raw count
     */
    public record ConditionStat(
            String condition,
            int occurrences,
            double frequencyPct,
            double mean12,
            double mean24,
            double mean48,
            double edge48,
            double hit48,
            double atr48,
            int effectiveN
    ) {}
}
