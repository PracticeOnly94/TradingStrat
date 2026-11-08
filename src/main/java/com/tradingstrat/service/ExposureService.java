package com.tradingstrat.service;

import com.tradingstrat.analysis.ExposureBacktester;
import com.tradingstrat.api.ChartDataClient;
import com.tradingstrat.api.SymbolUniverse;
import com.tradingstrat.model.ExtremeProfileRequest;
import com.tradingstrat.model.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
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
 * Runs the exposure sweep across a universe and ranks the configurations against holding.
 *
 * Results are averaged and median-ed per symbol rather than summed: the question is what
 * a rule does to a typical position, not to an imaginary book fully invested in
 * everything at once.
 */
@Service
public class ExposureService {

    private static final Logger log = LoggerFactory.getLogger(ExposureService.class);

    private final ChartDataClient chartDataClient;
    private final SymbolUniverse symbolUniverse;
    private final ExposureBacktester backtester;
    private final Semaphore fetchPermits;

    public ExposureService(ChartDataClient chartDataClient,
                           SymbolUniverse symbolUniverse,
                           ExposureBacktester backtester,
                           @Value("${tradingstrat.backtest.max-concurrent-symbols:5}") int maxConcurrent) {
        this.chartDataClient = chartDataClient;
        this.symbolUniverse = symbolUniverse;
        this.backtester = backtester;
        this.fetchPermits = new Semaphore(Math.max(1, maxConcurrent));
    }

    /**
     * @param medianReturnPct  the headline figure: half of symbols did better, half worse
     * @param symbolsBeatingHold how broadly it worked, not just how well on average
     */
    public record RuleSummary(
            String rule,
            double avgReturnPct,
            double medianReturnPct,
            double avgMaxDrawdownPct,
            double worstMaxDrawdownPct,
            double timeLongPct,
            double timeShortPct,
            double avgSwitches,
            int symbolsBeatingHold
    ) {}

    public Map<String, Object> run(ExtremeProfileRequest request) {
        List<String> symbols = request.symbols() != null && !request.symbols().isEmpty()
                ? request.symbols()
                : symbolUniverse.topByLiquidity(request.topSymbols() == null ? 20 : request.topSymbols());

        Interval interval = Interval.fromString(request.interval());
        java.time.Instant end = request.endInstant();
        double feePct = 0.1;

        List<Callable<Map<String, ExposureBacktester.Result>>> tasks = symbols.stream()
                .map(symbol -> (Callable<Map<String, ExposureBacktester.Result>>) () -> {
                    fetchPermits.acquire();
                    try {
                        return backtester.run(chartDataClient.fetchHistory(
                                symbol, interval.getValue(), request.bars(), end), feePct);
                    } finally {
                        fetchPermits.release();
                    }
                })
                .toList();

        Map<String, List<ExposureBacktester.Result>> byRule = new LinkedHashMap<>();
        int failures = 0;

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Map<String, ExposureBacktester.Result>> f : executor.invokeAll(tasks)) {
                try {
                    f.get().forEach((rule, res) ->
                            byRule.computeIfAbsent(rule, k -> new ArrayList<>()).add(res));
                } catch (Exception ex) {
                    failures++;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Exposure run interrupted", ex);
        }

        List<ExposureBacktester.Result> hold = byRule.get("HOLD");
        List<RuleSummary> summaries = new ArrayList<>();
        byRule.forEach((rule, results) -> {
            int beating = 0;
            for (int i = 0; hold != null && i < results.size() && i < hold.size(); i++) {
                if (results.get(i).totalReturnPct() > hold.get(i).totalReturnPct()) {
                    beating++;
                }
            }
            summaries.add(new RuleSummary(rule,
                    avg(results, ExposureBacktester.Result::totalReturnPct),
                    median(results.stream().map(ExposureBacktester.Result::totalReturnPct).toList()),
                    avg(results, ExposureBacktester.Result::maxDrawdownPct),
                    results.stream().mapToDouble(ExposureBacktester.Result::maxDrawdownPct).max().orElse(0),
                    avg(results, ExposureBacktester.Result::timeLongPct),
                    avg(results, ExposureBacktester.Result::timeShortPct),
                    avg(results, r -> (double) r.switches()),
                    beating));
        });

        // Median return first: robust to one symbol carrying the average.
        summaries.sort(Comparator.comparingDouble(RuleSummary::medianReturnPct).reversed());

        log.info("Exposure sweep: {} symbols, {} configs, {} failures",
                symbols.size() - failures, summaries.size(), failures);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("interval", interval.getValue());
        out.put("symbols", symbols.size() - failures);
        out.put("failures", failures);
        out.put("feePct", feePct);
        out.put("rules", summaries);
        return out;
    }

    private double avg(List<ExposureBacktester.Result> rs,
                       java.util.function.ToDoubleFunction<ExposureBacktester.Result> f) {
        return rs.isEmpty() ? 0 : rs.stream().mapToDouble(f).average().orElse(0);
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }
}
