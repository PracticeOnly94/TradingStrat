package com.tradingstrat.backtest;

import java.util.List;

/**
 * Aggregate results of backtesting one strategy across many symbols.
 *
 * The aggregate exists to reach a sample large enough to mean something; the per-symbol
 * rows are kept alongside it because an aggregate can hide the case where the whole
 * result rests on one or two markets.
 */
public record UniverseBacktestReport(
        String interval,
        String htfInterval,
        int symbolsRequested,
        int symbolsSucceeded,
        int symbolsFailed,
        Aggregate aggregate,
        List<SymbolRow> bySymbol,
        List<String> failures
) {

    /**
     * Pooled statistics over every trade in the universe.
     *
     * @param profitFactor  gross wins over gross losses, before fees
     * @param symbolsProfitable how many symbols finished with positive gross PnL —
     *                          a check on whether the result is broad or concentrated
     */
    public record Aggregate(
            int trades,
            int wins,
            int losses,
            double winRatePct,
            double avgWinPct,
            double avgLossPct,
            double grossPnlPct,
            double expectancyPct,
            double expectancyR,
            double netExpectancyPct,
            double profitFactor,
            int stopExits,
            int targetExits,
            int signalExits,
            int openAtEnd,
            double avgBarsHeld,
            int symbolsWithTrades,
            int symbolsProfitable,
            double medianSymbolPnlPct,
            int longTrades,
            double longPnlPct,
            int shortTrades,
            double shortPnlPct,
            double avgBuyAndHoldPct,
            double strategyMinusHoldPct
    ) {}

    /** One symbol's contribution. */
    public record SymbolRow(
            String symbol,
            int trades,
            int wins,
            double winRatePct,
            double grossPnlPct,
            double expectancyR,
            double avgBarsHeld,
            double buyAndHoldPct
    ) {}
}
