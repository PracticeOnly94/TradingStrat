package com.tradingstrat.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request to backtest the active strategy over historical candles.
 *
 * Example:
 * { "symbol": "BTCUSDT", "interval": "1h", "htfInterval": "4h", "bars": 3000 }
 *
 * @param htfInterval    higher timeframe for context, or null to run single-timeframe
 * @param bars           how many signal-timeframe candles to pull
 * @param includeTrades  whether to return the full trade list or only the summary
 */
public record BacktestRequest(
        @NotBlank String symbol,
        @NotBlank String interval,
        String htfInterval,
        @Min(300) @Max(20000) int bars,
        Double feePct,
        Double slippagePct,
        Integer warmupBars,
        boolean includeTrades,
        Integer maxHoldBars,
        Segment segment,
        String endDate
) {

    /**
     * Which part of the history to trade, for out-of-sample validation. Parameters chosen
     * by looking at {@link #IN_SAMPLE} can be checked against {@link #OUT_OF_SAMPLE},
     * which they never saw.
     */
    public enum Segment {
        /** The whole series. */
        ALL,
        /** The older 70%. */
        IN_SAMPLE,
        /** The most recent 30%. */
        OUT_OF_SAMPLE
    }

    /** Fraction of the series reserved for out-of-sample testing. */
    public static final double HOLDOUT_FRACTION = 0.30;

    public Segment segmentOrAll() {
        return segment == null ? Segment.ALL : segment;
    }

    /**
     * Last candle to include, from an ISO-8601 instant or date (e.g. "2025-01-01" or
     * "2025-01-01T00:00:00Z"). Null means the most recent data.
     */
    public java.time.Instant endInstant() {
        if (endDate == null || endDate.isBlank()) {
            return null;
        }
        String value = endDate.trim();
        return value.length() == 10
                ? java.time.LocalDate.parse(value).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                : java.time.Instant.parse(value);
    }
}
