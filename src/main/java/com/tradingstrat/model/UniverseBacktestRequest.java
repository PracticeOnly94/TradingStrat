package com.tradingstrat.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Backtest the active strategy across many symbols at once.
 *
 * One pair yields too few setups to separate edge from noise, so the point of this
 * request is sample size: {@code topSymbols} pulls the most liquid USDT pairs, or
 * {@code symbols} names them explicitly.
 *
 * Example:
 * { "topSymbols": 20, "interval": "1h", "htfInterval": "4h", "bars": 5000 }
 */
public record UniverseBacktestRequest(
        List<String> symbols,
        @Min(1) @Max(50) Integer topSymbols,
        @NotBlank String interval,
        String htfInterval,
        @Min(300) @Max(20000) int bars,
        Double feePct,
        Double slippagePct,
        Integer warmupBars,
        Integer maxHoldBars,
        BacktestRequest.Segment segment,
        String endDate
) {}
