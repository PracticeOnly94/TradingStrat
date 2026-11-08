package com.tradingstrat.model;

import java.util.Map;

/**
 * Groups chart data for multiple timeframes of the same symbol.
 * Passed to TradingAlgorithm so strategies can perform multi-timeframe analysis
 * (e.g. confirm a 1h signal against the 4h trend).
 */
public record ChartBundle(
        String symbol,
        Map<String, ChartData> charts   // interval value (e.g. "1h") -> ChartData
) {

    /** Convenience accessor by interval string, e.g. get("1h"). */
    public ChartData get(String interval) {
        return charts.get(interval);
    }

    /** Convenience accessor by Interval enum. */
    public ChartData get(Interval interval) {
        return charts.get(interval.getValue());
    }

    /** Returns true if chart data for the given interval was loaded. */
    public boolean has(String interval) {
        return charts.containsKey(interval);
    }

    /** Returns true if chart data for the given interval was loaded. */
    public boolean has(Interval interval) {
        return has(interval.getValue());
    }
}
