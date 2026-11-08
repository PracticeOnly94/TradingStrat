package com.tradingstrat.analysis;

import java.util.List;

/**
 * What indicators looked like at real extremes, against what they look like everywhere.
 *
 * The comparison is the whole point: a reading is only informative to the extent it
 * differs from baseline. "RSI was 34 at bottoms" says nothing until you know RSI is 51
 * on an average bar.
 */
public record ExtremeProfileReport(
        String interval,
        int symbols,
        int bars,
        int bottoms,
        int tops,
        ExtremeProfiler.Params params,
        List<IndicatorStat> stats,
        List<String> failures
) {

    /**
     * One indicator's behaviour at extremes.
     *
     * @param bottomMedian median value at bottoms
     * @param topMedian    median value at tops
     * @param baseMedian   median across all bars
     * @param bottomShift  bottom median minus baseline, in baseline IQRs — a
     *                     scale-free measure of how far the distribution moves
     * @param topShift     the same for tops
     * @param separation   distance between the bottom and top medians, in baseline IQRs;
     *                     high values mean the indicator distinguishes the two ends
     */
    public record IndicatorStat(
            String indicator,
            double bottomMedian,
            double topMedian,
            double baseMedian,
            double bottomShift,
            double topShift,
            double separation
    ) {}
}
