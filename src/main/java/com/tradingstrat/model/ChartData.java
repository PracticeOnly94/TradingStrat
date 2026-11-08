package com.tradingstrat.model;

import java.util.List;

public record ChartData(
        String symbol,
        String interval,
        List<Candle> candles
) {}
