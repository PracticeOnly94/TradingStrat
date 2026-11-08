package com.tradingstrat.algorithm.impl;

import com.tradingstrat.algorithm.TradingAlgorithm;
import com.tradingstrat.config.MomentumProperties;
import com.tradingstrat.indicator.CipherB;
import com.tradingstrat.indicator.Ta;
import com.tradingstrat.model.AnalysisResult;
import com.tradingstrat.model.ChartBundle;
import com.tradingstrat.model.ChartData;
import com.tradingstrat.model.Signal;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Momentum continuation: buy strength, sell weakness.
 *
 * <p>Entry is the <em>crossing</em> into an extreme rather than the extreme itself —
 * wt2 rising through +60 opens a long, falling through −60 opens a short. Using the
 * transition avoids re-entering on every bar of a long overbought stretch.
 *
 * <p>Exits are trend-following rather than target-based: the position is held until
 * price closes back through a short EMA, which is what "momentum is spent" looks like.
 * A wide ATR target remains as a backstop and an ATR stop caps the loss, but the exit
 * signal is intended to be what usually ends the trade.
 *
 * <p>This inverts {@link CipherBConfluenceAlgorithm}, which treats the same readings as
 * reversal signals. Both cannot be right, and the conditional forward-return study
 * favours this one — with the caveat that the hypothesis came from that same data.
 */
@Component
public class MomentumAlgorithm implements TradingAlgorithm {

    private final MomentumProperties props;

    public MomentumAlgorithm(MomentumProperties props) {
        this.props = props;
    }

    @Override
    public String key() {
        return "momentum";
    }

    @Override
    public AnalysisResult analyze(ChartBundle bundle) {
        Map<String, ChartData> charts = bundle.charts();
        if (charts == null || charts.isEmpty()) {
            return none(bundle.symbol(), "-", "No chart data available");
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
        if (i < 1 || isNaN(s.wt2()[i]) || isNaN(s.wt2()[i - 1]) || isNaN(s.atr()[i])) {
            return none(bundle.symbol(), interval, "Indicators not warm");
        }

        boolean crossedUp = s.wt2()[i - 1] < props.wt2Entry() && s.wt2()[i] >= props.wt2Entry();
        boolean crossedDown = s.wt2()[i - 1] > -props.wt2Entry() && s.wt2()[i] <= -props.wt2Entry();

        boolean rsiOkLong = props.rsiEntry() <= 0 || s.rsi()[i] >= props.rsiEntry();
        boolean rsiOkShort = props.rsiEntry() <= 0 || s.rsi()[i] <= 100 - props.rsiEntry();

        boolean bull = trendUp(s, i, true);
        boolean bear = trendUp(s, i, false);

        boolean goLong = props.tradeLongs() && crossedUp && rsiOkLong
                && (!props.requireBullTrend() || bull);
        boolean goShort = props.tradeShorts() && crossedDown && rsiOkShort
                && (!props.requireBullTrend() || bear);

        if (!goLong && !goShort) {
            return none(bundle.symbol(), interval, String.format(
                    "No momentum entry (wt2=%.1f rsi=%.1f)", s.wt2()[i], s.rsi()[i]));
        }

        boolean isLong = goLong;
        double entry = s.close()[i];
        double atr = s.atr()[i];
        double stop = isLong ? entry - props.atrStopMult() * atr : entry + props.atrStopMult() * atr;
        double target = isLong ? entry + props.atrTargetMult() * atr : entry - props.atrTargetMult() * atr;

        String reason = String.format("%s momentum: wt2 %.1f->%.1f rsi=%.1f trend=%s",
                isLong ? "LONG" : "SHORT", s.wt2()[i - 1], s.wt2()[i], s.rsi()[i],
                props.requireBullTrend() ? (isLong ? "bull" : "bear") : "ignored");

        return new AnalysisResult(bundle.symbol(), interval,
                isLong ? Signal.LONG : Signal.SHORT, stop, target, reason);
    }

    /**
     * Exit when momentum is spent: price closing back through the short EMA. Each value
     * depends only on bars up to its own index, so replay reads it safely.
     */
    @Override
    public boolean[] exitSignals(ChartData chart, Signal side) {
        CipherB.Series s = CipherB.computeSeries(chart.candles());
        double[] ema = Ta.ema(s.close(), props.exitEmaLen());
        int n = s.length();
        boolean[] out = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(ema[i])) {
                continue;
            }
            out[i] = side == Signal.LONG ? s.close()[i] < ema[i] : s.close()[i] > ema[i];
        }
        return out;
    }

    /** Long-horizon trend, used only when {@code requireBullTrend} is set. */
    private boolean trendUp(CipherB.Series s, int i, boolean up) {
        double[] ema = Ta.ema(s.close(), props.macroEmaLen());
        int back = i - props.macroBars();
        if (back < 0 || isNaN(ema[i]) || isNaN(ema[back])) {
            return false;
        }
        return up ? ema[i] > ema[back] : ema[i] < ema[back];
    }

    private boolean isNaN(double v) {
        return Double.isNaN(v);
    }

    private AnalysisResult none(String symbol, String interval, String reason) {
        return new AnalysisResult(symbol, interval, Signal.NONE, 0, 0, reason);
    }
}
