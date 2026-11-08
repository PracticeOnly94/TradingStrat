package com.tradingstrat.service;

import com.tradingstrat.analysis.ConditionProfiler;
import com.tradingstrat.analysis.ConditionReport;
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

/** Pools conditional forward returns across a universe of symbols. */
@Service
public class ConditionAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ConditionAnalysisService.class);

    private final ChartDataClient chartDataClient;
    private final SymbolUniverse symbolUniverse;
    private final ConditionProfiler profiler;
    private final Semaphore fetchPermits;

    public ConditionAnalysisService(ChartDataClient chartDataClient,
                                    SymbolUniverse symbolUniverse,
                                    ConditionProfiler profiler,
                                    @Value("${tradingstrat.backtest.max-concurrent-symbols:5}") int maxConcurrent) {
        this.chartDataClient = chartDataClient;
        this.symbolUniverse = symbolUniverse;
        this.profiler = profiler;
        this.fetchPermits = new Semaphore(Math.max(1, maxConcurrent));
    }

    public ConditionReport run(ExtremeProfileRequest request) {
        List<String> symbols = request.symbols() != null && !request.symbols().isEmpty()
                ? request.symbols()
                : symbolUniverse.topByLiquidity(request.topSymbols() == null ? 20 : request.topSymbols());

        Interval interval = Interval.fromString(request.interval());
        java.time.Instant end = request.endInstant();

        List<Callable<ConditionProfiler.Samples>> tasks = symbols.stream()
                .map(symbol -> (Callable<ConditionProfiler.Samples>) () -> {
                    fetchPermits.acquire();
                    try {
                        return profiler.profile(chartDataClient.fetchHistory(
                                symbol, interval.getValue(), request.bars(), end));
                    } finally {
                        fetchPermits.release();
                    }
                })
                .toList();

        Map<String, List<double[]>> pooled = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        int bars = 0;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ConditionProfiler.Samples>> futures = executor.invokeAll(tasks);
            for (int i = 0; i < futures.size(); i++) {
                try {
                    ConditionProfiler.Samples s = futures.get(i).get();
                    s.byCondition().forEach((k, v) ->
                            pooled.computeIfAbsent(k, x -> new ArrayList<>()).addAll(v));
                    bars += s.bars();
                } catch (Exception ex) {
                    failures.add(symbols.get(i) + ": " + ex.getMessage());
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Condition analysis interrupted", ex);
        }

        List<double[]> all = pooled.getOrDefault("ALL_BARS", List.of());
        double baseline48 = mean(all, 2);
        int longest = ConditionProfiler.HORIZONS[ConditionProfiler.HORIZONS.length - 1];

        final int totalBars = bars;
        List<ConditionReport.ConditionStat> stats = new ArrayList<>();
        pooled.forEach((condition, rows) -> stats.add(new ConditionReport.ConditionStat(
                condition,
                rows.size(),
                totalBars == 0 ? 0 : 100.0 * rows.size() / totalBars,
                mean(rows, 0), mean(rows, 1), mean(rows, 2),
                mean(rows, 2) - baseline48,
                hitRate(rows, 2),
                mean(rows, 5),
                Math.max(1, rows.size() / longest))));

        stats.sort(Comparator.comparingDouble(ConditionReport.ConditionStat::edge48).reversed());

        log.info("Conditions: {} symbols, {} bars, {} conditions, baseline 48-bar return {}%",
                symbols.size() - failures.size(), bars, stats.size(), baseline48);

        return new ConditionReport(interval.getValue(), symbols.size() - failures.size(),
                bars, ConditionProfiler.HORIZONS, stats, failures);
    }

    private double mean(List<double[]> rows, int col) {
        if (rows.isEmpty()) {
            return Double.NaN;
        }
        double sum = 0;
        int n = 0;
        for (double[] r : rows) {
            if (!Double.isNaN(r[col])) {
                sum += r[col];
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    private double hitRate(List<double[]> rows, int col) {
        if (rows.isEmpty()) {
            return Double.NaN;
        }
        int wins = 0;
        int n = 0;
        for (double[] r : rows) {
            if (!Double.isNaN(r[col])) {
                if (r[col] > 0) {
                    wins++;
                }
                n++;
            }
        }
        return n == 0 ? Double.NaN : 100.0 * wins / n;
    }
}
