package com.tradingstrat.service;

import com.tradingstrat.algorithm.TradingAlgorithm;
import com.tradingstrat.api.ChartDataClient;
import com.tradingstrat.backtest.BacktestEngine;
import com.tradingstrat.backtest.BacktestReport;
import com.tradingstrat.model.BacktestRequest;
import com.tradingstrat.model.ChartData;
import com.tradingstrat.model.Interval;
import org.springframework.stereotype.Service;

/**
 * Pulls the history a backtest needs and hands it to the engine.
 *
 * The higher timeframe is fetched with proportionally fewer bars, since it covers the
 * same wall-clock span with larger candles.
 */
@Service
public class BacktestService {

    private final ChartDataClient chartDataClient;
    private final BacktestEngine engine;
    private final TradingAlgorithm algorithm;

    public BacktestService(ChartDataClient chartDataClient,
                           BacktestEngine engine,
                           TradingAlgorithm algorithm) {
        this.chartDataClient = chartDataClient;
        this.engine = engine;
        this.algorithm = algorithm;
    }

    public BacktestReport run(BacktestRequest request) {
        Interval signal = Interval.fromString(request.interval());
        java.time.Instant endTime = request.endInstant();
        ChartData signalChart = chartDataClient.fetchHistory(
                request.symbol(), signal.getValue(), request.bars(), endTime);

        ChartData htfChart = null;
        if (request.htfInterval() != null && !request.htfInterval().isBlank()) {
            Interval htf = Interval.fromString(request.htfInterval());
            if (htf.duration().compareTo(signal.duration()) <= 0) {
                throw new IllegalArgumentException(
                        "htfInterval (" + htf.getValue() + ") must be longer than interval ("
                                + signal.getValue() + ")");
            }
            // Same wall-clock span, larger candles — plus headroom for indicator warm-up.
            long ratio = htf.duration().toMinutes() / signal.duration().toMinutes();
            int htfBars = (int) Math.min(20000, (request.bars() / ratio) + 300);
            htfChart = chartDataClient.fetchHistory(request.symbol(), htf.getValue(), htfBars, endTime);
        }

        signalChart = applySegment(signalChart, request.segmentOrAll(),
                request.warmupBars() != null
                        ? request.warmupBars()
                        : BacktestEngine.Params.defaults().warmupBars());

        BacktestEngine.Params defaults = BacktestEngine.Params.defaults();
        BacktestEngine.Params params = new BacktestEngine.Params(
                request.feePct() != null ? request.feePct() : defaults.feePct(),
                request.slippagePct() != null ? request.slippagePct() : defaults.slippagePct(),
                request.warmupBars() != null ? request.warmupBars() : defaults.warmupBars(),
                request.maxHoldBars() != null ? request.maxHoldBars() : defaults.maxHoldBars());

        BacktestReport report = engine.run(signalChart, htfChart, algorithm, params);

        return request.includeTrades()
                ? report
                : new BacktestReport(report.symbol(), report.signalInterval(), report.htfInterval(),
                report.bars(), report.params(), report.overall(), report.metricsBySide(), java.util.List.of());
    }

    /**
     * Trim the series to the requested segment.
     *
     * The out-of-sample slice keeps the preceding bars as history so indicators are warm
     * at its first tradable bar — the split is about which bars may be <em>traded</em>,
     * not about starting the indicators cold.
     */
    private ChartData applySegment(ChartData chart, BacktestRequest.Segment segment, int warmup) {
        if (segment == BacktestRequest.Segment.ALL) {
            return chart;
        }
        var candles = chart.candles();
        int n = candles.size();
        int splitAt = (int) (n * (1 - BacktestRequest.HOLDOUT_FRACTION));

        if (segment == BacktestRequest.Segment.IN_SAMPLE) {
            return new ChartData(chart.symbol(), chart.interval(), candles.subList(0, splitAt));
        }
        int from = Math.max(0, splitAt - warmup);
        return new ChartData(chart.symbol(), chart.interval(), candles.subList(from, n));
    }
}
