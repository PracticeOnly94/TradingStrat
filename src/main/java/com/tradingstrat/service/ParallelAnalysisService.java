package com.tradingstrat.service;

import com.tradingstrat.model.AnalysisOutcome;
import com.tradingstrat.model.AnalysisRequest;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Processes a list of symbol requests concurrently using Java 21 virtual threads.
 * Each symbol (with all its requested intervals) is handled in its own virtual thread.
 * Per-symbol failures are captured in their {@link AnalysisOutcome}; the batch always
 * returns one outcome per request, in the original order.
 */
@Service
public class ParallelAnalysisService {

    private final ChartAnalysisService chartAnalysisService;

    public ParallelAnalysisService(ChartAnalysisService chartAnalysisService) {
        this.chartAnalysisService = chartAnalysisService;
    }

    public List<AnalysisOutcome> analyzeAll(List<AnalysisRequest> requests) {
        // analyzeSafely never throws, so each task always yields an outcome.
        List<Callable<AnalysisOutcome>> tasks = requests.stream()
                .map(req -> (Callable<AnalysisOutcome>) () -> chartAnalysisService.analyzeSafely(req))
                .toList();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return executor.invokeAll(tasks).stream()
                    .map(ParallelAnalysisService::getOutcome)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Parallel analysis interrupted", e);
        }
    }

    private static AnalysisOutcome getOutcome(Future<AnalysisOutcome> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Task interrupted", e);
        } catch (ExecutionException e) {
            // Should not happen: analyzeSafely catches its own errors.
            throw new RuntimeException("Unexpected task failure", e.getCause());
        }
    }
}
