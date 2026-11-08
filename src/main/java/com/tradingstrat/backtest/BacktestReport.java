package com.tradingstrat.backtest;

import com.tradingstrat.model.ChartData;
import com.tradingstrat.model.Signal;
import com.tradingstrat.ta4j.BarSeriesAdapter;
import org.ta4j.core.AnalysisCriterion;
import org.ta4j.core.BarSeries;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.criteria.ExpectancyCriterion;
import org.ta4j.core.criteria.NumberOfPositionsCriterion;
import org.ta4j.core.criteria.SqnCriterion;
import org.ta4j.core.criteria.drawdown.MaximumDrawdownCriterion;
import org.ta4j.core.criteria.drawdown.ReturnOverMaxDrawdownCriterion;
import org.ta4j.core.criteria.pnl.NetProfitLossRatioCriterion;
import org.ta4j.core.criteria.pnl.NetReturnCriterion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Results of a backtest run.
 *
 * Headline counts are computed here; the risk and return metrics come from ta4j's
 * criteria so the arithmetic is the library's tested implementation rather than ours.
 * Metrics are reported per side because long and short performance can differ wildly,
 * and an aggregate can hide one side losing money.
 */
public record BacktestReport(
        String symbol,
        String signalInterval,
        String htfInterval,
        int bars,
        BacktestEngine.Params params,
        Summary overall,
        Map<String, Map<String, Double>> metricsBySide,
        List<BacktestEngine.BacktestTrade> trades
) {

    /**
     * Trade counts and gross outcomes. Gross excludes fees; see
     * {@link #metricsBySide()} for the fee-aware figures.
     *
     * @param profitFactor gross wins divided by gross losses; above 1 means the winners
     *                     outweighed the losers before costs
     */
    public record Summary(
            int trades,
            int wins,
            int losses,
            double winRatePct,
            double avgWinPct,
            double avgLossPct,
            double grossPnlPct,
            double expectancyR,
            double totalR,
            double profitFactor,
            int stopExits,
            int targetExits,
            int signalExits,
            int openAtEnd,
            double avgBarsHeld,
            int longTrades,
            double longPnlPct,
            int shortTrades,
            double shortPnlPct,
            double buyAndHoldPct
    ) {}

    static BacktestReport build(ChartData signalChart, ChartData htfChart,
                                BacktestEngine.Params params,
                                List<BacktestEngine.BacktestTrade> trades,
                                Map<String, TradingRecord> records) {

        BarSeries series = BarSeriesAdapter.toBarSeries(signalChart);

        Map<String, Map<String, Double>> metrics = new LinkedHashMap<>();
        records.forEach((side, record) -> metrics.put(side, criteria(series, record)));

        return new BacktestReport(
                signalChart.symbol(),
                signalChart.interval(),
                htfChart == null ? null : htfChart.interval(),
                signalChart.candles().size(),
                params,
                summarise(trades, buyAndHold(signalChart, params.warmupBars())),
                metrics,
                trades);
    }

    private static Map<String, Double> criteria(BarSeries series, TradingRecord record) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("positions", value(new NumberOfPositionsCriterion(), series, record));
        // Net variants: these subtract the transaction costs carried by the record.
        out.put("netReturn", value(new NetReturnCriterion(), series, record));
        out.put("expectancy", value(new ExpectancyCriterion(), series, record));
        out.put("profitLossRatio", value(new NetProfitLossRatioCriterion(), series, record));
        out.put("maxDrawdown", value(new MaximumDrawdownCriterion(), series, record));
        out.put("returnOverMaxDrawdown", value(new ReturnOverMaxDrawdownCriterion(), series, record));
        out.put("sqn", value(new SqnCriterion(), series, record));
        return out;
    }

    private static double value(AnalysisCriterion criterion, BarSeries series, TradingRecord record) {
        try {
            double v = criterion.calculate(series, record).doubleValue();
            return Double.isFinite(v) ? v : 0.0;
        } catch (RuntimeException ex) {
            return 0.0;   // criteria can divide by zero on an empty record
        }
    }

    /**
     * Return from simply holding the instrument over the tested span — the benchmark any
     * long-biased strategy has to beat to be worth running at all.
     */
    private static double buyAndHold(ChartData chart, int warmupBars) {
        List<com.tradingstrat.model.Candle> candles = chart.candles();
        int from = Math.min(Math.max(warmupBars, 0), candles.size() - 1);
        double start = candles.get(from).close();
        double end = candles.get(candles.size() - 1).close();
        return start == 0 ? 0 : (end - start) / start * 100.0;
    }

    private static Summary summarise(List<BacktestEngine.BacktestTrade> trades, double buyAndHold) {
        if (trades.isEmpty()) {
            return new Summary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, buyAndHold);
        }

        int wins = 0;
        int losses = 0;
        double winSum = 0;
        double lossSum = 0;
        double total = 0;
        double totalR = 0;
        int stops = 0;
        int targets = 0;
        int signals = 0;
        int openAtEnd = 0;
        long barsHeld = 0;
        int longTrades = 0;
        int shortTrades = 0;
        double longPnl = 0;
        double shortPnl = 0;

        for (BacktestEngine.BacktestTrade t : trades) {
            total += t.grossPct();
            totalR += t.rMultiple();
            barsHeld += t.barsHeld();
            if (t.grossPct() > 0) {
                wins++;
                winSum += t.grossPct();
            } else {
                losses++;
                lossSum += Math.abs(t.grossPct());
            }
            switch (t.exitReason()) {
                case "stop" -> stops++;
                case "target" -> targets++;
                case "signal" -> signals++;
                default -> openAtEnd++;
            }
            if (t.side() == Signal.LONG) {
                longTrades++;
                longPnl += t.grossPct();
            } else {
                shortTrades++;
                shortPnl += t.grossPct();
            }
        }

        return new Summary(
                trades.size(), wins, losses,
                100.0 * wins / trades.size(),
                wins == 0 ? 0 : winSum / wins,
                losses == 0 ? 0 : lossSum / losses,
                total,
                totalR / trades.size(),
                totalR,
                lossSum == 0 ? Double.POSITIVE_INFINITY : winSum / lossSum,
                stops, targets, signals, openAtEnd,
                (double) barsHeld / trades.size(),
                longTrades, longPnl, shortTrades, shortPnl,
                buyAndHold);
    }

    /** Count of trades on one side, for quick inspection. */
    public long tradesOn(Signal side) {
        return trades.stream().filter(t -> t.side() == side).count();
    }
}
