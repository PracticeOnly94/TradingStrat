package com.tradingstrat.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Study what indicators look like at real tops and bottoms.
 *
 * Example:
 * { "topSymbols": 20, "interval": "1h", "bars": 5000, "minMoveAtr": 3.0 }
 *
 * @param pivotBars  bars either side an extreme must exceed
 * @param forwardBars window in which the following move is measured
 * @param minMoveAtr how large that move must be, in ATRs, to count as significant
 */
public record ExtremeProfileRequest(
        List<String> symbols,
        @Min(1) @Max(50) Integer topSymbols,
        @NotBlank String interval,
        @Min(500) @Max(20000) int bars,
        Integer pivotBars,
        Integer forwardBars,
        Double minMoveAtr,
        String endDate
) {

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
