package com.tradingstrat.service;

import com.tradingstrat.analysis.ExtremeProfileReport;
import com.tradingstrat.analysis.ExtremeProfiler;
import com.tradingstrat.api.ChartDataClient;
import com.tradingstrat.api.SymbolUniverse;
import com.tradingstrat.model.ExtremeProfileRequest;
import com.tradingstrat.model.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Pools indicator readings at extremes across many symbols.
 *
 * Individual symbols yield only a handful of significant tops and bottoms, so the
 * question "what do bottoms have in common" can only be asked of a pooled sample. The
 * indicators are all scale-free, which is what makes pooling legitimate.
 */
@Service
public class ExtremeAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ExtremeAnalysisService.class);

    private final ChartDataClient chartDataClient;
    private final SymbolUniverse symbolUniverse;
    private final ExtremeProfiler profiler;
    private final Semaphore fetchPermits;

    public ExtremeAnalysisService(ChartDataClient chartDataClient,
                                  SymbolUniverse symbolUniverse,
                                  ExtremeProfiler profiler,
                                  @Value("${tradingstrat.backtest.max-concurrent-symbols:5}") int maxConcurrent) {
        this.chartDataClient = chartDataClient;
        this.symbolUniverse = symbolUniverse;
        this.profiler = profiler;
        this.fetchPermits = new Semaphore(Math.max(1, maxConcurrent));
    }

    public ExtremeProfileReport run(ExtremeProfileRequest request) {
        List<String> symbols = request.symbols() != null && !request.symbols().isEmpty()
                ? request.symbols()
                : symbolUniverse.topByLiquidity(request.topSymbols() == null ? 20 : request.topSymbols());

        ExtremeProfiler.Params params = new ExtremeProfiler.Params(
                request.pivotBars() == null ? 24 : request.pivotBars(),
                request.forwardBars() == null ? 48 : request.forwardBars(),
                request.minMoveAtr() == null ? 3.0 : request.minMoveAtr());

        Interval interval = Interval.fromString(request.interval());
        java.time.Instant end = request.endInstant();

        List<Callable<ExtremeProfiler.Samples>> tasks = symbols.stream()
                .map(symbol -> (Callable<ExtremeProfiler.Samples>) () -> {
                    fetchPermits.acquire();
                    try {
                        return profiler.profile(
                                chartDataClient.fetchHistory(symbol, interval.getValue(), request.bars(), end),
                                params);
                    } finally {
                        fetchPermits.release();
                    }
                })
                .toList();

        Map<String, List<Double>> bottoms = new LinkedHashMap<>();
        Map<String, List<Double>> tops = new LinkedHashMap<>();
        Map<String, List<Double>> baseline = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        int bottomCount = 0;
        int topCount = 0;
        int barCount = 0;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ExtremeProfiler.Samples>> futures = executor.invokeAll(tasks);
            for (int i = 0; i < futures.size(); i++) {
                try {
                    ExtremeProfiler.Samples s = futures.get(i).get();
                    merge(bottoms, s.atBottoms());
                    merge(tops, s.atTops());
                    merge(baseline, s.baseline());
                    bottomCount += s.bottoms();
                    topCount += s.tops();
                    barCount += s.bars();
                } catch (Exception ex) {
                    failures.add(symbols.get(i) + ": " + ex.getMessage());
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Extreme analysis interrupted", ex);
        }

        log.info("Extremes: {} symbols, {} bars, {} bottoms, {} tops",
                symbols.size() - failures.size(), barCount, bottomCount, topCount);

        List<ExtremeProfileReport.IndicatorStat> stats = new ArrayList<>();
        for (String indicator : baseline.keySet()) {
            // Boolean flags are summarised as occurrence rates: the median of a 0/1
            // series is 0 for anything happening less than half the time, which would
            // hide exactly the rare conditions worth knowing about. Their "shift" is a
            // lift — how many times more often the condition holds at extremes.
            boolean isFlag = indicator.startsWith("b_");
            double base = isFlag ? mean(baseline.get(indicator)) : median(baseline.get(indicator));
            double bottom = isFlag ? mean(bottoms.get(indicator)) : median(bottoms.get(indicator));
            double top = isFlag ? mean(tops.get(indicator)) : median(tops.get(indicator));

            double bottomShift;
            double topShift;
            double separation;
            if (isFlag) {
                bottomShift = base <= 0 ? Double.NaN : bottom / base;
                topShift = base <= 0 ? Double.NaN : top / base;
                separation = Math.abs(bottom - top);
            } else {
                double iqr = iqr(baseline.get(indicator));
                double scale = iqr <= 0 ? 1 : iqr;
                bottomShift = (bottom - base) / scale;
                topShift = (top - base) / scale;
                separation = Math.abs(bottom - top) / scale;
            }
            stats.add(new ExtremeProfileReport.IndicatorStat(
                    indicator, bottom, top, base, bottomShift, topShift, separation));
        }
        // Most discriminating first: the indicators that best separate tops from bottoms.
        stats.sort(Comparator.comparingDouble(
                (ExtremeProfileReport.IndicatorStat s) -> Math.abs(s.separation())).reversed());

        return new ExtremeProfileReport(interval.getValue(), symbols.size() - failures.size(),
                barCount, bottomCount, topCount, params, stats, failures);
    }

    private void merge(Map<String, List<Double>> target, Map<String, List<Double>> source) {
        source.forEach((k, v) -> target.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
    }

    private double median(List<Double> values) {
        return quantile(values, 0.5);
    }

    /** Mean, used for 0/1 flags where it is the occurrence rate. */
    private double mean(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0;
        int n = 0;
        for (double v : values) {
            if (!Double.isNaN(v)) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** Interquartile range: the spread of ordinary readings, used to scale the shifts. */
    private double iqr(List<Double> values) {
        return quantile(values, 0.75) - quantile(values, 0.25);
    }

    private double quantile(List<Double> values, double q) {
        if (values == null || values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> clean = values.stream().filter(v -> !Double.isNaN(v)).sorted().toList();
        if (clean.isEmpty()) {
            return Double.NaN;
        }
        int idx = (int) Math.round(q * (clean.size() - 1));
        return clean.get(Math.max(0, Math.min(clean.size() - 1, idx)));
    }
}
