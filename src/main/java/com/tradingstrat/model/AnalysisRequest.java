package com.tradingstrat.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request to analyse one symbol across one or more timeframes.
 *
 * Example — multi-timeframe:
 * { "symbol": "BTCUSDT", "intervals": ["1h", "4h"] }
 *
 * Example — single timeframe:
 * { "symbol": "BTCUSDT", "intervals": ["1h"] }
 *
 * Supported intervals: 15m, 1h, 4h, 1d
 */
public record AnalysisRequest(
        @NotBlank String symbol,
        @NotEmpty List<@NotBlank String> intervals
) {}
