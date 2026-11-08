package com.tradingstrat.backtest;

import com.tradingstrat.algorithm.TradingAlgorithm;
import com.tradingstrat.model.AnalysisResult;
import com.tradingstrat.model.Candle;
import com.tradingstrat.model.ChartBundle;
import com.tradingstrat.model.ChartData;
import com.tradingstrat.model.Interval;
import com.tradingstrat.model.Signal;
import com.tradingstrat.ta4j.BarSeriesAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseTradingRecord;
import org.ta4j.core.Trade;
import org.ta4j.core.TradingRecord;
import org.ta4j.core.analysis.cost.LinearTransactionCostModel;
import org.ta4j.core.analysis.cost.ZeroCostModel;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bar-by-bar backtester.
 *
 * <h2>Avoiding lookahead</h2>
 * The strategy is evaluated at bar {@code i} against a slice containing only bars
 * {@code 0..i}, so it cannot see the future even accidentally — indicators are
 * recomputed on the truncated history rather than once over the whole series.
 * Entries fill at the <em>next</em> bar's open, mirroring ta4j's
 * {@link org.ta4j.core.backtest.TradeOnNextOpenModel}, because a signal derived from
 * bar {@code i}'s close cannot also be filled at that close.
 *
 * <h2>Higher timeframe</h2>
 * For a multi-timeframe strategy the HTF slice contains only bars that had actually
 * <em>closed</em> before the current signal bar opened. Using the in-progress HTF bar
 * would leak future information — the single most common bug in multi-timeframe
 * backtests.
 *
 * <h2>Fills</h2>
 * A position exits at whichever of stop or target the price reaches first. When a
 * single bar spans both, the stop is assumed to hit first: intrabar order is unknowable
 * from candles, so the pessimistic reading is the honest one.
 *
 * Trades are recorded into ta4j {@link TradingRecord}s (longs and shorts separately,
 * since a record carries one side) so that ta4j's tested performance criteria, rather
 * than hand-rolled arithmetic, produce the reported metrics.
 */
@Component
public class BacktestEngine {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);

    /**
     * @param feePct      per-side fee, in percent of notional (e.g. 0.1 = 0.1%)
     * @param slippagePct adverse price movement applied to every fill, in percent
     * @param warmupBars  bars reserved for indicator warm-up before trading starts
     */
    /**
     * @param maxHoldBars close the position after this many bars regardless of price;
     *                    0 disables it. A measured edge is a <em>fixed-horizon</em>
     *                    forward return, so harvesting exactly what was measured means
     *                    exiting on the same horizon rather than on a target.
     */
    public record Params(double feePct, double slippagePct, int warmupBars, int maxHoldBars) {
        public static Params defaults() {
            return new Params(0.1, 0.02, 250, 0);
        }
    }

    /** One simulated round trip. Prices include slippage; {@code grossPct} excludes fees. */
    public record BacktestTrade(
            Signal side,
            Instant entryTime,
            double entryPrice,
            Instant exitTime,
            double exitPrice,
            String exitReason,
            double grossPct,
            double rMultiple,
            int barsHeld,
            String signalReason
    ) {}

    /**
     * Run the strategy across the history.
     *
     * @param signalChart the timeframe signals are taken on
     * @param htfChart    higher timeframe for context, or null
     */
    public BacktestReport run(ChartData signalChart, ChartData htfChart,
                              TradingAlgorithm algorithm, Params params) {

        List<Candle> candles = signalChart.candles();
        int n = candles.size();
        String symbol = signalChart.symbol();

        Duration htfDuration = htfChart == null
                ? null
                : Interval.fromString(htfChart.interval()).duration();

        // Signal-based exits, if the strategy provides them. Computed once over the full
        // series; every value is causal, so reading index k during replay is safe.
        boolean[] longExits = algorithm.exitSignals(signalChart, Signal.LONG);
        boolean[] shortExits = algorithm.exitSignals(signalChart, Signal.SHORT);

        List<BacktestTrade> trades = new ArrayList<>();
        int evaluated = 0;
        int signalled = 0;

        int i = Math.max(params.warmupBars(), 1);
        while (i < n - 1) {
            AnalysisResult result;
            try {
                result = algorithm.analyze(bundleAt(symbol, signalChart, htfChart, htfDuration, i));
                evaluated++;
            } catch (RuntimeException ex) {
                // Not enough warm-up yet for some indicator; step forward and retry.
                i++;
                continue;
            }

            if (result.signal() == Signal.NONE) {
                i++;
                continue;
            }
            signalled++;

            boolean isLong = result.signal() == Signal.LONG;
            int entryIdx = i + 1;
            double entryPrice = applySlippage(candles.get(entryIdx).open(), params.slippagePct(), isLong, true);
            double stop = result.stopLoss();
            double target = result.target();

            // Walk forward for the first touch of stop or target, or an exit signal.
            boolean[] exits = isLong ? longExits : shortExits;
            int exitIdx = -1;
            double exitPrice = 0;
            String exitReason = null;
            for (int k = entryIdx; k < n; k++) {
                Candle bar = candles.get(k);
                boolean hitStop = isLong ? bar.low() <= stop : bar.high() >= stop;
                boolean hitTarget = isLong ? bar.high() >= target : bar.low() <= target;
                if (hitStop) {                       // pessimistic when a bar spans both
                    exitIdx = k;
                    exitPrice = stop;
                    exitReason = "stop";
                    break;
                }
                if (hitTarget) {
                    exitIdx = k;
                    exitPrice = target;
                    exitReason = "target";
                    break;
                }
                // Signal exit fills at the close of the bar that produced it: the signal
                // is only known once that bar has completed.
                if (exits != null && k > entryIdx && exits[k]) {
                    exitIdx = k;
                    exitPrice = bar.close();
                    exitReason = "signal";
                    break;
                }
                // Time exit: the horizon the edge was measured over has elapsed.
                if (params.maxHoldBars() > 0 && k - entryIdx >= params.maxHoldBars()) {
                    exitIdx = k;
                    exitPrice = bar.close();
                    exitReason = "time";
                    break;
                }
            }
            if (exitIdx < 0) {
                exitIdx = n - 1;
                exitPrice = candles.get(exitIdx).close();
                exitReason = "open-at-end";
            }

            exitPrice = applySlippage(exitPrice, params.slippagePct(), isLong, false);
            double grossPct = isLong
                    ? (exitPrice - entryPrice) / entryPrice * 100.0
                    : (entryPrice - exitPrice) / entryPrice * 100.0;

            // R multiple: profit measured in units of the risk actually taken. Stop
            // distances vary per trade, so percentages are not comparable across trades
            // and averaging them mixes incommensurable numbers.
            double riskPerUnit = Math.abs(entryPrice - stop);
            double rMultiple = riskPerUnit <= 0 ? 0
                    : (isLong ? exitPrice - entryPrice : entryPrice - exitPrice) / riskPerUnit;

            trades.add(new BacktestTrade(
                    result.signal(),
                    candles.get(entryIdx).timestamp(), entryPrice,
                    candles.get(exitIdx).timestamp(), exitPrice,
                    exitReason, grossPct, rMultiple, exitIdx - entryIdx,
                    result.reason()));

            // One position at a time: resume after the exit bar.
            i = exitIdx + 1;
        }

        log.info("Backtest {} {}: {} bars, {} evaluations, {} signals, {} trades",
                symbol, signalChart.interval(), n, evaluated, signalled, trades.size());

        return BacktestReport.build(signalChart, htfChart, params, trades,
                buildRecords(signalChart, trades, params));
    }

    /** Longs and shorts recorded separately: a ta4j record carries a single side. */
    private Map<String, TradingRecord> buildRecords(ChartData chart,
                                                    List<BacktestTrade> trades,
                                                    Params params) {
        BarSeries series = BarSeriesAdapter.toBarSeries(chart);
        LinearTransactionCostModel fees = new LinearTransactionCostModel(params.feePct() / 100.0);

        TradingRecord longs = new BaseTradingRecord(Trade.TradeType.BUY, fees, new ZeroCostModel());
        TradingRecord shorts = new BaseTradingRecord(Trade.TradeType.SELL, fees, new ZeroCostModel());

        Map<Instant, Integer> indexByTime = new LinkedHashMap<>();
        List<Candle> candles = chart.candles();
        for (int k = 0; k < candles.size(); k++) {
            indexByTime.put(candles.get(k).timestamp(), k);
        }

        for (BacktestTrade t : trades) {
            TradingRecord record = t.side() == Signal.LONG ? longs : shorts;
            Integer entryIdx = indexByTime.get(t.entryTime());
            Integer exitIdx = indexByTime.get(t.exitTime());
            if (entryIdx == null || exitIdx == null) {
                continue;
            }
            record.enter(entryIdx, series.numFactory().numOf(t.entryPrice()),
                    series.numFactory().one());
            record.exit(exitIdx, series.numFactory().numOf(t.exitPrice()),
                    series.numFactory().one());
        }

        Map<String, TradingRecord> records = new LinkedHashMap<>();
        records.put("long", longs);
        records.put("short", shorts);
        return records;
    }

    /**
     * Build the bundle the strategy sees at bar {@code i}: the signal timeframe truncated
     * at {@code i}, plus any higher-timeframe bars that had closed by then.
     */
    private ChartBundle bundleAt(String symbol, ChartData signalChart, ChartData htfChart,
                                 Duration htfDuration, int i) {
        Map<String, ChartData> charts = new LinkedHashMap<>();
        List<Candle> signalCandles = signalChart.candles();
        charts.put(signalChart.interval(), new ChartData(
                symbol, signalChart.interval(), signalCandles.subList(0, i + 1)));

        if (htfChart != null) {
            Instant now = signalCandles.get(i).timestamp();
            int lastClosed = lastClosedIndex(htfChart.candles(), now, htfDuration);
            if (lastClosed >= 0) {
                charts.put(htfChart.interval(), new ChartData(
                        symbol, htfChart.interval(), htfChart.candles().subList(0, lastClosed + 1)));
            }
        }
        return new ChartBundle(symbol, charts);
    }

    /** Index of the last higher-timeframe bar that had fully closed at {@code now}. */
    private int lastClosedIndex(List<Candle> htfCandles, Instant now, Duration htfDuration) {
        int last = -1;
        for (int j = 0; j < htfCandles.size(); j++) {
            Instant close = htfCandles.get(j).timestamp().plus(htfDuration);
            if (close.isAfter(now)) {
                break;
            }
            last = j;
        }
        return last;
    }

    /** Slippage always works against the trade. */
    private double applySlippage(double price, double slippagePct, boolean isLong, boolean entering) {
        double factor = slippagePct / 100.0;
        boolean payMore = isLong == entering;   // buying to open, or buying to close a short
        return payMore ? price * (1 + factor) : price * (1 - factor);
    }
}
