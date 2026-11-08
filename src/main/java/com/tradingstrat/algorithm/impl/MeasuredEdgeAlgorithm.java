package com.tradingstrat.algorithm.impl;

import com.tradingstrat.algorithm.TradingAlgorithm;
import com.tradingstrat.config.MeasuredEdgeProperties;
import com.tradingstrat.indicator.CipherB;
import com.tradingstrat.indicator.Ta;
import com.tradingstrat.model.AnalysisResult;
import com.tradingstrat.model.ChartBundle;
import com.tradingstrat.model.ChartData;
import com.tradingstrat.model.Signal;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The only two setups that survived out-of-sample testing.
 *
 * <ul>
 *   <li><b>Long, bull regime:</b> {@code rsi < 30 and wt2 <= -53} — measured at +1.78%
 *       over 48 bars against a −1.56% baseline, positive in all five years on symbols
 *       the search never saw.</li>
 *   <li><b>Short, bear regime:</b> {@code wt2 >= 60 and bearDiv} — +1.15% per short with
 *       a 60.7% hit rate, the highest of any setup tested.</li>
 * </ul>
 *
 * <p>Regime is a gate, not a score: the same conditions measured negative in the opposite
 * regime, so trading them regime-blind pools two different populations. Nothing else is
 * checked, because everything else that was tried either failed to replicate or reduced
 * the sample without adding edge.
 *
 * <p>Exits belong to the engine's {@code maxHoldBars}, matching the 48-bar horizon the
 * edge was measured over. The ATR stop exists to bound loss, not to take profit.
 */
@Component
public class MeasuredEdgeAlgorithm implements TradingAlgorithm {

    /** Horizon the edge was measured over. */
    public static final int MEASURED_HORIZON = 48;

    private final MeasuredEdgeProperties props;

    public MeasuredEdgeAlgorithm(MeasuredEdgeProperties props) {
        this.props = props;
    }

    @Override
    public String key() {
        return "measured-edge";
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
        double[] macro = Ta.ema(s.close(), props.macroEmaLen());
        int back = i - props.macroBars();
        if (back < 0 || isNaN(macro[i]) || isNaN(macro[back]) || isNaN(s.atr()[i])) {
            return none(bundle.symbol(), interval, "Indicators not warm");
        }
        boolean bull = macro[i] > macro[back];

        double rsi = s.rsi()[i];
        double wt2 = s.wt2()[i];

        boolean goLong = props.tradeLongs() && bull
                && rsi < props.longRsiMax() && wt2 <= props.longWt2Max();
        boolean goShort = props.tradeShorts() && !bull
                && wt2 >= props.shortWt2Min()
                && (!props.requireBearDiv() || s.bearDiv()[i]);

        if (!goLong && !goShort) {
            return none(bundle.symbol(), interval, String.format(
                    "No setup (regime=%s rsi=%.1f wt2=%.1f)", bull ? "bull" : "bear", rsi, wt2));
        }

        boolean isLong = goLong;
        double entry = s.close()[i];
        double atr = s.atr()[i];

        // A zero multiplier parks the stop far away, reproducing the unstopped measurement.
        double stopDistance = props.atrStopMult() > 0
                ? props.atrStopMult() * atr
                : entry * 0.95;
        double stop = isLong ? entry - stopDistance : entry + stopDistance;
        // Target far beyond reach: the time exit is meant to end these trades.
        double target = isLong ? entry * 100 : entry * 0.01;

        String reason = String.format("%s %s regime rsi=%.1f wt2=%.1f%s",
                isLong ? "LONG" : "SHORT", bull ? "bull" : "bear", rsi, wt2,
                isLong ? "" : (s.bearDiv()[i] ? " bearDiv" : ""));

        return new AnalysisResult(bundle.symbol(), interval,
                isLong ? Signal.LONG : Signal.SHORT, stop, target, reason);
    }

    private boolean isNaN(double v) {
        return Double.isNaN(v);
    }

    private AnalysisResult none(String symbol, String interval, String reason) {
        return new AnalysisResult(symbol, interval, Signal.NONE, 0, 0, reason);
    }
}
