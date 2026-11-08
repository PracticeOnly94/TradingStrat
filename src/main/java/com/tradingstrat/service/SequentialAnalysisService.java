package com.tradingstrat.service;

import com.tradingstrat.model.AnalysisOutcome;
import com.tradingstrat.model.AnalysisRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Processes a list of symbol requests one by one, in order.
 * A failure on one symbol is captured in its {@link AnalysisOutcome}
 * and does not stop the remaining symbols.
 */
@Service
public class SequentialAnalysisService {

    private final ChartAnalysisService chartAnalysisService;

    public SequentialAnalysisService(ChartAnalysisService chartAnalysisService) {
        this.chartAnalysisService = chartAnalysisService;
    }

    public List<AnalysisOutcome> analyzeAll(List<AnalysisRequest> requests) {
        List<AnalysisOutcome> outcomes = new ArrayList<>(requests.size());
        for (AnalysisRequest request : requests) {
            outcomes.add(chartAnalysisService.analyzeSafely(request));
        }
        return outcomes;
    }
}
