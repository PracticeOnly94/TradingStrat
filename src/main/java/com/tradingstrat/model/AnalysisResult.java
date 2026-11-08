package com.tradingstrat.model;

public record AnalysisResult(
        String symbol,
        String interval,
        Signal signal,
        double stopLoss,
        double target,
        String reason
) {}
