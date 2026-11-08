package com.tradingstrat.model;

/**
 * Per-symbol result of a batch analysis. Wraps either a successful
 * {@link AnalysisResult} or an error message, so that one failing symbol
 * does not sink an entire batch request.
 */
public record AnalysisOutcome(
        String symbol,
        boolean success,
        AnalysisResult result,   // populated when success == true
        String error             // populated when success == false
) {

    public static AnalysisOutcome ok(AnalysisResult result) {
        return new AnalysisOutcome(result.symbol(), true, result, null);
    }

    public static AnalysisOutcome failed(String symbol, String error) {
        return new AnalysisOutcome(symbol, false, null, error);
    }
}
