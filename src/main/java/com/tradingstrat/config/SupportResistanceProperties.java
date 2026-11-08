package com.tradingstrat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Support/resistance detection and how stops sit against it
 * ({@code tradingstrat.sr.*}).
 *
 * @param lookbackBars        how far back to collect pivots
 * @param pivotLeft           bars required to the left of a pivot
 * @param pivotRight          bars required to the right (also the confirmation lag)
 * @param clusterTolerancePct pivots within this % of a cluster's mean join it
 * @param minTouches          pivots a cluster needs before it counts as a level
 * @param stopBufferPct       how far beyond the level to place the stop, as a % of price
 * @param targetBufferPct     how far short of the level to place the target, as a % of price
 */
@ConfigurationProperties(prefix = "tradingstrat.sr")
public record SupportResistanceProperties(
        int lookbackBars,
        int pivotLeft,
        int pivotRight,
        double clusterTolerancePct,
        int minTouches,
        double stopBufferPct,
        double targetBufferPct
) {}
