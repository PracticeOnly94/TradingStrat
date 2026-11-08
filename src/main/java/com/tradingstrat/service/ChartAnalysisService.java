package com.tradingstrat.service;

import com.tradingstrat.algorithm.TradingAlgorithm;
import com.tradingstrat.api.ChartDataClient;
import com.tradingstrat.model.AnalysisOutcome;
import com.tradingstrat.model.AnalysisRequest;
import com.tradingstrat.model.AnalysisResult;
import com.tradingstrat.model.ChartBundle;
import com.tradingstrat.model.ChartData;
import com.tradingstrat.model.Interval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Core per-symbol service: fetches chart data for all requested intervals,
 * assembles a ChartBundle, and runs the algorithm.
 *
 * Both master services (sequential and parallel) delegate here.
 */
@Service
public class ChartAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ChartAnalysisService.class);

    private final ChartDataClient chartDataClient;
    private final TradingAlgorithm tradingAlgorithm;

    public ChartAnalysisService(ChartDataClient chartDataClient, TradingAlgorithm tradingAlgorithm) {
        this.chartDataClient = chartDataClient;
        this.tradingAlgorithm = tradingAlgorithm;
    }

    public AnalysisResult analyze(AnalysisRequest request) {
        Map<String, ChartData> charts = new LinkedHashMap<>();

        for (String interval : request.intervals()) {
            // Validate the interval early — throws IllegalArgumentException on unknown values
            Interval validated = Interval.fromString(interval);
            ChartData chartData = chartDataClient.fetchChart(request.symbol(), validated.getValue());
            charts.put(validated.getValue(), chartData);
        }

        ChartBundle bundle = new ChartBundle(request.symbol().toUpperCase(), charts);
        return tradingAlgorithm.analyze(bundle);
    }

    /**
     * Same as {@link #analyze(AnalysisRequest)} but never throws: any failure
     * (bad interval, API error, etc.) is captured into a failed
     * {@link AnalysisOutcome} so callers can isolate per-symbol errors in a batch.
     */
    public AnalysisOutcome analyzeSafely(AnalysisRequest request) {
        try {
            return AnalysisOutcome.ok(analyze(request));
        } catch (Exception ex) {
            log.warn("Analysis failed for symbol {}: {}", request.symbol(), ex.getMessage());
            return AnalysisOutcome.failed(request.symbol(), ex.getMessage());
        }
    }
}
