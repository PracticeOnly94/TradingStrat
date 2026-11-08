package com.tradingstrat.algorithm.impl;

import com.tradingstrat.algorithm.TradingAlgorithm;
import com.tradingstrat.config.RangeRsiProperties;
import com.tradingstrat.indicator.CipherB;
import com.tradingstrat.indicator.Ta;
import com.tradingstrat.model.AnalysisResult;
import com.tradingstrat.model.ChartBundle;
import com.tradingstrat.model.ChartData;
import com.tradingstrat.model.Signal;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Long when price sits at the bottom of its recent range with RSI oversold.
 *
 * <p>Two conditions, no regime gate, no confluence stack — the form that measured
 * positive in 8 of 8 windows across 50 distinct symbols.
 *
 * <p>The entry timing is the experiment. A conditional study counts every bar where the
 * setup holds, but a position occupies its symbol for the whole horizon, so entering on
 * the first qualifying bar takes the earliest and generally worst price of each oversold
 * stretch. {@code entryOnRecovery} instead waits for RSI to climb back through the
 * threshold — the "recovering out of oversold" reading, which is also closer to how the
 * setup would be traded by hand.
 */
@Component
public class RangeRsiAlgorithm implements TradingAlgorithm {

    private final RangeRsiProperties props;

    public RangeRsiAlgorithm(RangeRsiProperties props) {
        this.props = props;
    }

    @Override
    public String key() {
        return "range-rsi";
    }

    @Override
    public AnalysisResult analyze(ChartBundle bundle) {
        Map<String, ChartData> charts = bundle.charts();
        if (charts == null || charts.isEmpty()) {
            return none(bundle.symbol(), "-", "No chart data");
        }
        ChartData chart = charts.values().iterator().next();
        String interval = chart.interval();

        CipherB.Series s;
        try {
            s = CipherB.computeSeries(chart.candles());
        } catch (IllegalArgumentException ex) {
            return none(bundle.symbol(), interval, ex.getMessage());
        }

        int i = s.last();
        if (i < 1 || isNaN(s.atr()[i]) || isNaN(s.rsi()[i]) || isNaN(s.rsi()[i - 1])) {
            return none(bundle.symbol(), interval, "Indicators not warm");
        }

        double[] high = Ta.highest(s.high(), props.rangeLookback());
        double[] low = Ta.lowest(s.low(), props.rangeLookback());
        if (isNaN(high[i]) || isNaN(low[i]) || high[i] <= low[i]) {
            return none(bundle.symbol(), interval, "Range undefined");
        }

        double close = s.close()[i];
        double pctile = (close - low[i]) / (high[i] - low[i]) * 100.0;
        boolean inRangeLow = pctile <= props.rangePctileMax();

        boolean trigger;
        if (props.entryOnRecovery()) {
            // The setup resolving: RSI was below the threshold and has just crossed back
            // above it, with price still in the lower part of the range.
            trigger = inRangeLow
                    && s.rsi()[i - 1] < props.rsiMax()
                    && s.rsi()[i] >= props.rsiMax();
        } else {
            trigger = inRangeLow && s.rsi()[i] < props.rsiMax();
        }

        if (!trigger) {
            return none(bundle.symbol(), interval, String.format(
                    "No setup (pctile=%.0f rsi=%.1f)", pctile, s.rsi()[i]));
        }

        double atr = s.atr()[i];
        double stopDistance = props.atrStopMult() > 0 ? props.atrStopMult() * atr : close * 0.95;
        double stop = close - stopDistance;
        // Out of reach on purpose: the engine's time exit is meant to close these.
        double target = close * 100;

        return new AnalysisResult(bundle.symbol(), interval, Signal.LONG, stop, target,
                String.format("LONG rangePctile=%.0f rsi=%.1f%s", pctile, s.rsi()[i],
                        props.entryOnRecovery() ? " (recovery)" : " (immediate)"));
    }

    private boolean isNaN(double v) {
        return Double.isNaN(v);
    }

    private AnalysisResult none(String symbol, String interval, String reason) {
        return new AnalysisResult(symbol, interval, Signal.NONE, 0, 0, reason);
    }
}
