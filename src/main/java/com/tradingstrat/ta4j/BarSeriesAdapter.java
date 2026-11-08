package com.tradingstrat.ta4j;

import com.tradingstrat.model.Candle;
import com.tradingstrat.model.ChartData;
import com.tradingstrat.model.Interval;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.num.DoubleNumFactory;

import java.time.Duration;

/**
 * Converts our {@link ChartData} into a ta4j {@link BarSeries}.
 *
 * This is the single boundary between our model and ta4j, and it is deliberately
 * one-way: the {@code indicator} package stays on our own {@link Candle} type so the
 * Pine-faithful Cipher B stack never depends on ta4j's numerics.
 *
 * <p><b>Numerics.</b> ta4j defaults to {@code DecimalNumFactory} (BigDecimal). That is
 * accurate but very slow — clustering indicators over a few hundred bars took minutes
 * with it and milliseconds with {@link DoubleNumFactory}. Price data does not need
 * arbitrary precision here, so doubles are used throughout, matching the {@code double}
 * arithmetic our own indicators already use.
 */
public final class BarSeriesAdapter {

    private BarSeriesAdapter() {}

    public static BarSeries toBarSeries(ChartData chart) {
        Duration period = Interval.fromString(chart.interval()).duration();
        BarSeries series = new BaseBarSeriesBuilder()
                .withNumFactory(DoubleNumFactory.getInstance())
                .withName(chart.symbol() + " " + chart.interval())
                .build();

        for (Candle c : chart.candles()) {
            series.barBuilder()
                    .timePeriod(period)
                    // ta4j bars are keyed by end time; our candles carry open time.
                    .endTime(c.timestamp().plus(period))
                    .openPrice(c.open())
                    .highPrice(c.high())
                    .lowPrice(c.low())
                    .closePrice(c.close())
                    .volume(c.volume())
                    .add();
        }
        return series;
    }
}
