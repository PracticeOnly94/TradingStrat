package com.tradingstrat.model;

import java.time.Instant;

public record Candle(
        Instant timestamp,
        double open,
        double high,
        double low,
        double close,
        double volume
) {}
