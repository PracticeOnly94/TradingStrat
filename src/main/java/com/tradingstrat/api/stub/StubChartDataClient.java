package com.tradingstrat.api.stub;

import com.tradingstrat.api.ChartDataClient;
import com.tradingstrat.model.Candle;
import com.tradingstrat.model.ChartData;
import com.tradingstrat.model.Interval;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Stub implementation that returns synthetic candle data.
 * Only active when the "stub" Spring profile is enabled:
 *   --spring.profiles.active=stub
 */
@Profile("stub")
@Component
public class StubChartDataClient implements ChartDataClient {

    /** Matches the production default-limit so indicators have the same warm-up room. */
    private static final int CANDLE_COUNT = 300;

    private final Random random = new Random();

    @Override
    public ChartData fetchChart(String symbol, String interval) {
        return fetchHistory(symbol, interval, CANDLE_COUNT);
    }

    @Override
    public ChartData fetchHistory(String symbol, String interval, int bars, java.time.Instant endTime) {
        Interval validated = Interval.fromString(interval);
        return new ChartData(symbol.toUpperCase(), validated.getValue(),
                generateCandles(intervalSeconds(validated), bars));
    }

    /** Spaces synthetic candles realistically for the requested timeframe. */
    private long intervalSeconds(Interval interval) {
        return switch (interval) {
            case FIFTEEN_MINUTES -> 15 * 60L;
            case ONE_HOUR -> 60 * 60L;
            case FOUR_HOURS -> 4 * 60 * 60L;
            case ONE_DAY -> 24 * 60 * 60L;
        };
    }

    private List<Candle> generateCandles(long stepSeconds, int count) {
        List<Candle> candles = new ArrayList<>(count);
        double price = 100 + random.nextDouble() * 900;

        for (int i = count; i > 0; i--) {
            double open = price;
            double change = (random.nextDouble() - 0.5) * price * 0.02;
            double close = open + change;
            double high = Math.max(open, close) + random.nextDouble() * price * 0.01;
            double low = Math.min(open, close) - random.nextDouble() * price * 0.01;
            double volume = 1000 + random.nextDouble() * 9000;

            candles.add(new Candle(
                    Instant.now().minusSeconds(i * stepSeconds),
                    open, high, low, close, volume
            ));

            price = close;
        }

        return candles;
    }
}
