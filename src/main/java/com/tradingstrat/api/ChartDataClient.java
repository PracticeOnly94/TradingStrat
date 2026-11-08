package com.tradingstrat.api;

import com.tradingstrat.model.ChartData;

import java.time.Instant;

/**
 * Abstraction over chart data sources (e.g. TradingView, Binance).
 * Implementations handle HTTP and deserialization; callers see only ChartData.
 */
public interface ChartDataClient {

    ChartData fetchChart(String symbol, String interval);

    /**
     * Fetch the most recent {@code bars} candles, paging beyond the provider's
     * per-request cap where necessary. Used by the backtester, which needs far more
     * history than a single live analysis does.
     *
     * @return up to {@code bars} candles, oldest → newest; fewer if the provider runs out
     */
    default ChartData fetchHistory(String symbol, String interval, int bars) {
        return fetchHistory(symbol, interval, bars, null);
    }

    /**
     * As {@link #fetchHistory(String, String, int)}, but ending at {@code endTime} rather
     * than at the present. Needed to test a strategy against a past regime: parameters
     * tuned in one market condition should be checked against a different one.
     *
     * @param endTime last candle to include, or null for the most recent
     */
    ChartData fetchHistory(String symbol, String interval, int bars, Instant endTime);
}
