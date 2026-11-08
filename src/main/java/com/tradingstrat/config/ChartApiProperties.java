package com.tradingstrat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the {@code tradingstrat.chart-api.*} configuration block.
 */
@ConfigurationProperties(prefix = "tradingstrat.chart-api")
public record ChartApiProperties(
        String baseUrl,
        int timeoutMs,
        int defaultLimit
) {}
