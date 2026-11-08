package com.tradingstrat.service;

import com.tradingstrat.analysis.CombinationProfiler;
import com.tradingstrat.analysis.CombinationReport;
import com.tradingstrat.api.ChartDataClient;
import com.tradingstrat.api.SymbolUniverse;
import com.tradingstrat.model.CombinationSearchRequest;
import com.tradingstrat.model.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Searches indicator combinations under a strict search/holdout split.
 *
 * <p>The protocol is the whole point. Combinations are scored on the search windows
 * only; anything that is positive in every one of them is then carried, unchanged, to
 * holdout windows the search never touched. Survivors of the search half are candidates,
 * not findings — with ~1,900 hypotheses per regime, dozens will clear a three-window
 * filter by luck, which is exactly what the holdout is there to expose.
 */
@Service
public class CombinationSearchService {

    private static final Logger log = LoggerFactory.getLogger(CombinationSearchService.class);

    private final ChartDataClient chartDataClient;
    private final SymbolUniverse symbolUniverse;
    private final CombinationProfiler profiler;
    private final Semaphore fetchPermits;

    public CombinationSearchService(ChartDataClient chartDataClient,
                                    SymbolUniverse symbolUniverse,
                                    CombinationProfiler profiler,
                                    @Value("${tradingstrat.backtest.max-concurrent-symbols:5}") int maxConcurrent) {
        this.chartDataClient = chartDataClient;
        this.symbolUniverse = symbolUniverse;
        this.profiler = profiler;
        this.fetchPermits = new Semaphore(Math.max(1, maxConcurrent));
    }

    public CombinationReport run(CombinationSearchRequest request) {
        List<String> symbols = request.symbols() != null && !request.symbols().isEmpty()
                ? request.symbols()
                : symbolUniverse.topByLiquidity(request.topSymbols() == null ? 20 : request.topSymbols());

        Interval interval = Interval.fromString(request.interval());
        int maxSize = request.maxCombinationSize() == null ? 4 : request.maxCombinationSize();
        int minOccurrences = request.minOccurrences() == null ? 30 : request.minOccurrences();
        int[] combos = profiler.combinations(maxSize);

        List<CombinationProfiler.WindowStats> search = new ArrayList<>();
        for (String window : request.searchWindows()) {
            search.add(profiler.profile(load(symbols, interval, request.bars(), window), combos));
        }
        List<CombinationProfiler.WindowStats> holdout = new ArrayList<>();
        for (String window : request.holdoutWindows()) {
            holdout.add(profiler.profile(load(symbols, interval, request.bars(), window), combos));
        }

        List<CombinationReport.Survivor> bull =
                survivors(combos, search, holdout, minOccurrences, true);
        List<CombinationReport.Survivor> bear =
                survivors(combos, search, holdout, minOccurrences, false);

        List<CombinationReport.Pinned> pinned = pinned(request, combos, search, holdout);

        // Chance of a coin-flip combination clearing every search window, times the
        // number of hypotheses. If survivors are near this, they mean nothing.
        double expectedFalse = combos.length * Math.pow(0.5, request.searchWindows().size());

        List<String> notes = List.of(
                "Search-half results are candidates, not findings.",
                "Expected survivors by chance alone: ~" + Math.round(expectedFalse) + " per regime.",
                "Only holdoutPassed=true combinations carry any evidential weight.",
                "Forward windows overlap heavily, so effective sample is far below the counts.");

        log.info("Combination search: {} hypotheses, {} bull survivors, {} bear survivors",
                combos.length, bull.size(), bear.size());

        return new CombinationReport(interval.getValue(), maxSize, combos.length,
                request.searchWindows(), request.holdoutWindows(), minOccurrences,
                expectedFalse, bull.size(), bear.size(), bull, bear, pinned, notes);
    }

    /** Named combinations, reported across every window whether they pass or fail. */
    private List<CombinationReport.Pinned> pinned(CombinationSearchRequest request, int[] combos,
                                                  List<CombinationProfiler.WindowStats> search,
                                                  List<CombinationProfiler.WindowStats> holdout) {
        if (request.pinnedCombinations() == null || request.pinnedCombinations().isEmpty()) {
            return List.of();
        }

        Map<Integer, Integer> indexByMask = new HashMap<>();
        for (int i = 0; i < combos.length; i++) {
            indexByMask.put(combos[i], i);
        }

        List<String> windowLabels = new ArrayList<>();
        request.searchWindows().forEach(w -> windowLabels.add("search:" + label(w)));
        request.holdoutWindows().forEach(w -> windowLabels.add("holdout:" + label(w)));

        List<CombinationProfiler.WindowStats> all = new ArrayList<>(search);
        all.addAll(holdout);

        List<CombinationReport.Pinned> out = new ArrayList<>();
        for (List<String> names : request.pinnedCombinations()) {
            int mask = profiler.maskOf(names);
            Integer idx = indexByMask.get(mask);
            if (idx == null) {
                throw new IllegalArgumentException("Pinned combination " + names
                        + " exceeds maxCombinationSize " + request.maxCombinationSize());
            }

            List<Double> bullEdges = new ArrayList<>();
            List<Integer> bullCounts = new ArrayList<>();
            List<Double> bullHits = new ArrayList<>();
            List<Double> bullMeans = new ArrayList<>();
            List<Double> bullBases = new ArrayList<>();
            List<Double> bearEdges = new ArrayList<>();
            List<Integer> bearCounts = new ArrayList<>();
            List<Double> bearHits = new ArrayList<>();
            List<Double> bearMeans = new ArrayList<>();
            List<Double> bearBases = new ArrayList<>();

            for (CombinationProfiler.WindowStats w : all) {
                bullEdges.add(round(w.bull().edge(idx)));
                bullCounts.add((int) w.bull().counts()[idx]);
                bullHits.add(round(w.bull().hitRate(idx)));
                bullMeans.add(round(w.bull().mean(idx)));
                bullBases.add(round(w.bull().baselineMean()));
                bearEdges.add(round(w.bear().edge(idx)));
                bearCounts.add((int) w.bear().counts()[idx]);
                bearHits.add(round(w.bear().hitRate(idx)));
                bearMeans.add(round(w.bear().mean(idx)));
                bearBases.add(round(w.bear().baselineMean()));
            }

            out.add(new CombinationReport.Pinned(profiler.describe(mask), windowLabels,
                    bullEdges, bullCounts, bullHits, bullMeans, bullBases,
                    bearEdges, bearCounts, bearHits, bearMeans, bearBases));
        }
        return out;
    }

    private String label(String window) {
        return window == null || window.isBlank() ? "recent" : window;
    }

    /** Combinations positive and sufficiently frequent in every search window. */
    private List<CombinationReport.Survivor> survivors(int[] combos,
                                                       List<CombinationProfiler.WindowStats> search,
                                                       List<CombinationProfiler.WindowStats> holdout,
                                                       int minOccurrences,
                                                       boolean bullRegime) {
        List<CombinationReport.Survivor> out = new ArrayList<>();

        for (int c = 0; c < combos.length; c++) {
            List<Integer> counts = new ArrayList<>();
            List<Double> edges = new ArrayList<>();
            double worst = Double.MAX_VALUE;
            boolean qualifies = true;

            for (CombinationProfiler.WindowStats w : search) {
                CombinationProfiler.RegimeStats r = bullRegime ? w.bull() : w.bear();
                long n = r.counts()[c];
                double edge = r.edge(c);
                counts.add((int) n);
                edges.add(round(edge));
                if (n < minOccurrences || Double.isNaN(edge) || edge <= 0) {
                    qualifies = false;
                    break;
                }
                worst = Math.min(worst, edge);
            }
            if (!qualifies) {
                continue;
            }

            List<Integer> hCounts = new ArrayList<>();
            List<Double> hEdges = new ArrayList<>();
            double worstHoldout = Double.MAX_VALUE;
            double hitSum = 0;
            int hitWindows = 0;
            boolean passed = true;

            for (CombinationProfiler.WindowStats w : holdout) {
                CombinationProfiler.RegimeStats r = bullRegime ? w.bull() : w.bear();
                long n = r.counts()[c];
                double edge = r.edge(c);
                hCounts.add((int) n);
                hEdges.add(round(edge));
                if (n < minOccurrences || Double.isNaN(edge) || edge <= 0) {
                    passed = false;
                }
                if (!Double.isNaN(edge)) {
                    worstHoldout = Math.min(worstHoldout, edge);
                }
                if (!Double.isNaN(r.hitRate(c))) {
                    hitSum += r.hitRate(c);
                    hitWindows++;
                }
            }

            out.add(new CombinationReport.Survivor(
                    profiler.describe(combos[c]), Integer.bitCount(combos[c]),
                    counts, edges, round(worst),
                    hCounts, hEdges,
                    round(worstHoldout == Double.MAX_VALUE ? Double.NaN : worstHoldout),
                    round(hitWindows == 0 ? Double.NaN : hitSum / hitWindows),
                    passed));
        }

        // Holdout survivors first, then by how badly the search half could do.
        out.sort(Comparator
                .comparing(CombinationReport.Survivor::holdoutPassed).reversed()
                .thenComparing(Comparator.comparingDouble(
                        CombinationReport.Survivor::worstHoldoutEdge).reversed()));
        return out;
    }

    private double round(double v) {
        return Double.isNaN(v) ? Double.NaN : Math.round(v * 1000.0) / 1000.0;
    }

    private List<com.tradingstrat.model.ChartData> load(List<String> symbols, Interval interval,
                                                        int bars, String endDate) {
        Instant end = endDate == null || endDate.isBlank() ? null
                : (endDate.trim().length() == 10
                ? java.time.LocalDate.parse(endDate.trim()).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                : Instant.parse(endDate.trim()));

        List<Callable<com.tradingstrat.model.ChartData>> tasks = symbols.stream()
                .map(symbol -> (Callable<com.tradingstrat.model.ChartData>) () -> {
                    fetchPermits.acquire();
                    try {
                        return chartDataClient.fetchHistory(symbol, interval.getValue(), bars, end);
                    } finally {
                        fetchPermits.release();
                    }
                })
                .toList();

        List<com.tradingstrat.model.ChartData> charts = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<com.tradingstrat.model.ChartData> f : executor.invokeAll(tasks)) {
                try {
                    charts.add(f.get());
                } catch (Exception ex) {
                    log.warn("Fetch failed in window {}: {}", endDate, ex.getMessage());
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Combination search interrupted", ex);
        }
        return charts;
    }
}
