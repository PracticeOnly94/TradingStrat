package com.tradingstrat.algorithm;

import com.tradingstrat.model.AnalysisResult;
import com.tradingstrat.model.ChartBundle;
import com.tradingstrat.model.ChartData;
import com.tradingstrat.model.Signal;

/**
 * Contract for trading strategy algorithms.
 *
 * Each implementation receives a ChartBundle containing chart data for all
 * requested timeframes, allowing multi-timeframe analysis
 * (e.g. check the 1h trend, confirm against the 4h trend).
 */
public interface TradingAlgorithm {

    /**
     * Stable identifier used to select the active strategy via configuration
     * ({@code tradingstrat.algorithm}). Must be unique across implementations.
     */
    String key();

    AnalysisResult analyze(ChartBundle bundle);

    /**
     * Optional signal-based exits, as a flag per bar of {@code chart}.
     *
     * Returning null means positions are managed purely by the stop and target carried
     * on the {@link AnalysisResult}. Implementations that do return a series must make
     * every value at index {@code i} depend only on bars {@code <= i}, since the
     * backtester reads it during replay and a forward-looking value would silently
     * fabricate profits.
     *
     * @param side the direction of the open position the exit would close
     */
    default boolean[] exitSignals(ChartData chart, Signal side) {
        return null;
    }
}
