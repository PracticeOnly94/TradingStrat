package com.tradingstrat.algorithm.impl;

import com.tradingstrat.algorithm.TradingAlgorithm;
import com.tradingstrat.config.ConfluenceProperties;
import com.tradingstrat.config.ExitMode;
import com.tradingstrat.config.SupportResistanceProperties;
import com.tradingstrat.config.TrendMode;
import com.tradingstrat.config.TrendWindow;
import com.tradingstrat.indicator.CipherB;
import com.tradingstrat.indicator.SupportResistance;
import com.tradingstrat.indicator.Ta;
import com.tradingstrat.model.AnalysisResult;
import com.tradingstrat.model.ChartBundle;
import com.tradingstrat.model.ChartData;
import com.tradingstrat.model.Signal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cipher B confluence strategy.
 *
 * A dot on the signal timeframe is the <em>trigger</em>; it never fires on its own.
 *
 * Three conditions are <b>mandatory gates</b> — all must hold or there is no signal,
 * regardless of the score. They are evaluated <em>together across one window</em>: the
 * anchor dot X and the latest dot Y define the span, and every gate is measured over it.
 * <ol>
 *   <li><b>dotTrendline</b> — the line from X to Y slopes up (long) or down (short).</li>
 *   <li><b>priceTrend</b> — the trend EMA slopes the same way from X to Y, with price on
 *       the correct side of it at the current bar.</li>
 *   <li><b>moneyFlow</b> — the RSI+MFI ribbon rises (long) / falls (short) from X to Y.</li>
 * </ol>
 *
 * With {@link com.tradingstrat.config.DotAnchor#ANY} the anchor is searched: the first
 * candidate where all three hold together wins, so the gates always agree on one window.
 *
 * The remaining conditions are <b>scored</b>; the signal is taken once at least
 * {@code tradingstrat.confluence.min-score} of them pass:
 * <ol>
 *   <li><b>rsi</b> — RSI is inside the recovery band and turning up (long), or inside
 *       the rolling-over band and turning down (short).</li>
 *   <li><b>wtVwap</b> — Cipher B's {@code wtVwap} (wt1 - wt2) is above zero (long) /
 *       below zero (short), i.e. WaveTrend momentum has flipped.</li>
 *   <li><b>htfTrendGlobal</b> — the higher timeframe's macro EMA slopes the same way.</li>
 *   <li><b>htfTrendLocal</b> — the higher timeframe's recent EMA slope agrees too.</li>
 *   <li><b>htfMoneyFlow</b> — the higher timeframe's ribbon agrees.</li>
 * </ol>
 *
 * Both higher-timeframe trend conditions can be promoted to gates via
 * {@code htf-trend-global-mode} and {@code htf-trend-local-mode}, so each one's cost in
 * sample size can be measured rather than assumed.
 *
 * The signal timeframe is the first requested interval; the higher timeframe is the last.
 * With only one interval the higher-timeframe conditions are unavailable.
 *
 * Every gate and scored condition is reported in the result's reason string.
 */
@Component
public class CipherBConfluenceAlgorithm implements TradingAlgorithm {

    private final ConfluenceProperties props;
    private final SupportResistanceProperties srProps;

    public CipherBConfluenceAlgorithm(ConfluenceProperties props,
                                      SupportResistanceProperties srProps) {
        this.props = props;
        this.srProps = srProps;
    }

    @Override
    public String key() {
        return "cipher-b-confluence";
    }

    /** One confluence condition, mandatory or scored. */
    private record Check(String name, boolean passed) {}

    /** Outcome of evaluating one direction: the mandatory gates plus the scored extras. */
    private record Eval(List<Check> gates, List<Check> extras, DotWindow window) {

        boolean gatesPassed() {
            return gates.stream().allMatch(Check::passed);
        }

        int score() {
            return (int) extras.stream().filter(Check::passed).count();
        }
    }

    /**
     * A candidate dot pair and the three mandatory gates measured across it.
     * {@code anchorIdx < 0} means no pair could be formed, so no window exists.
     */
    private record DotWindow(int anchorIdx, int latestIdx,
                             boolean trendline, boolean priceTrend, boolean moneyFlow) {

        int passed() {
            return (trendline ? 1 : 0) + (priceTrend ? 1 : 0) + (moneyFlow ? 1 : 0);
        }

        boolean valid() {
            return anchorIdx >= 0;
        }

        int spanBars() {
            return latestIdx - anchorIdx;
        }
    }

    @Override
    public AnalysisResult analyze(ChartBundle bundle) {
        Map<String, ChartData> charts = bundle.charts();
        if (charts == null || charts.isEmpty()) {
            return none(bundle.symbol(), "-", "No chart data available");
        }

        List<ChartData> ordered = new ArrayList<>(charts.values());
        ChartData signalChart = ordered.get(0);
        ChartData htfChart = ordered.size() > 1 ? ordered.get(ordered.size() - 1) : null;
        String interval = htfChart == null
                ? signalChart.interval()
                : signalChart.interval() + ">" + htfChart.interval();

        CipherB.Series s;
        CipherB.Series htf = null;
        try {
            s = CipherB.computeSeries(signalChart.candles());
            if (htfChart != null) {
                htf = CipherB.computeSeries(htfChart.candles());
            }
        } catch (IllegalArgumentException ex) {
            return none(bundle.symbol(), interval, ex.getMessage());
        }

        int i = s.last();
        boolean longTrigger = props.tradeLongs()
                && recentQualifyingDot(s, i, true);
        boolean shortTrigger = props.tradeShorts()
                && recentQualifyingDot(s, i, false);

        if (!longTrigger && !shortTrigger) {
            return none(bundle.symbol(), interval,
                    "No Cipher B dot within the last " + props.dotMaxAgeBars() + " bars");
        }

        // Only LEVELS exits consult support/resistance, and clustering pivots is the most
        // expensive step in the pass — skip it entirely for the ATR modes.
        SupportResistance.Result sr = props.exitMode() == ExitMode.LEVELS
                ? SupportResistance.compute(s.high(), s.low(), new SupportResistance.Params(
                        srProps.lookbackBars(), srProps.pivotLeft(), srProps.pivotRight(),
                        srProps.clusterTolerancePct(), srProps.minTouches()))
                : new SupportResistance.Result(List.of());
        // MACRO uses a long EMA read at the signal bar; DOT_WINDOW uses a shorter one
        // read across the anchor window. One array either way.
        int emaLen = props.trendWindow() == TrendWindow.MACRO
                ? props.macroTrendEmaLen()
                : props.trendEmaLen();
        double[] ema = Ta.ema(s.close(), emaLen);

        // Two independent higher-timeframe horizons: a long one for regime and a short
        // one for recent direction. Kept separate so each can be measured on its own.
        HtfContext htfCtx = htf == null ? null : new HtfContext(
                htf,
                Ta.ema(htf.close(), props.macroTrendEmaLen()),
                Ta.ema(htf.close(), props.htfLocalEmaLen()));

        Eval longEval = longTrigger ? evaluateSide(s, ema, htfCtx, i, true) : null;
        Eval shortEval = shortTrigger ? evaluateSide(s, ema, htfCtx, i, false) : null;

        // How many scored conditions actually exist depends on the configured modes and
        // whether a higher timeframe was supplied, so it is read off the evaluation.
        Eval reference = longEval != null ? longEval : shortEval;
        int available = reference.extras().size();
        int required = Math.min(props.minScore(), available);

        boolean takeLong = longEval != null && longEval.gatesPassed() && longEval.score() >= required;
        boolean takeShort = shortEval != null && shortEval.gatesPassed() && shortEval.score() >= required;

        // Opposing dots in the same window: prefer the stronger, and stand aside on a tie.
        if (takeLong && takeShort) {
            if (longEval.score() > shortEval.score()) {
                takeShort = false;
            } else if (shortEval.score() > longEval.score()) {
                takeLong = false;
            } else {
                return none(bundle.symbol(), interval,
                        "Conflicting long/short triggers with equal score " + longEval.score());
            }
        }

        if (takeLong) {
            return withLevels(bundle.symbol(), interval, Signal.LONG, s, i,
                    describe("LONG", longEval, available), sr);
        }
        if (takeShort) {
            return withLevels(bundle.symbol(), interval, Signal.SHORT, s, i,
                    describe("SHORT", shortEval, available), sr);
        }

        // Report the side that triggered; if both did, the one that got further.
        String side;
        Eval eval;
        if (longEval != null && (shortEval == null || longEval.score() >= shortEval.score())) {
            side = "LONG";
            eval = longEval;
        } else {
            side = "SHORT";
            eval = shortEval;
        }
        String why = eval.gatesPassed()
                ? " — below required " + required
                : " — mandatory gate failed";
        return none(bundle.symbol(), interval, describe(side, eval, available) + why);
    }

    /** Higher-timeframe series with its two trend EMAs, computed once per request. */
    private record HtfContext(CipherB.Series series, double[] macroEma, double[] localEma) {}

    /** Evaluate the mandatory gates and the scored extras for the given direction. */
    private Eval evaluateSide(CipherB.Series s, double[] ema,
                              HtfContext htf, int i, boolean isLong) {
        DotWindow window = selectWindow(s, ema, i, isLong);

        List<Check> gates = new ArrayList<>(5);
        gates.add(new Check("dotTrendline", window.trendline()));
        gates.add(new Check("moneyFlow", window.moneyFlow()));

        List<Check> extras = new ArrayList<>(5);
        place(gates, extras, props.rsiMode(), "rsi", rsiBand(s.rsi(), i, isLong));
        place(gates, extras, props.wtVwapMode(), "wtVwap", momentumFlipped(s.wtVwap(), i, isLong));
        place(gates, extras, props.trendMode(), "priceTrend", window.priceTrend());

        if (htf != null) {
            int j = htf.series().last();
            place(gates, extras, props.htfTrendGlobalMode(), "htfTrendGlobal",
                    emaSloping(htf.macroEma(), j, props.macroTrendBars(), isLong));
            place(gates, extras, props.htfTrendLocalMode(), "htfTrendLocal",
                    emaSloping(htf.localEma(), j, props.htfLocalBars(), isLong));
            // The money-flow half of the old htfContext. "Not overbought" is dropped:
            // it measured 100% at green dots, so it scored a point for nothing.
            extras.add(new Check("htfMoneyFlow",
                    rising(htf.series().moneyFlow(), j, props.mfLookback(), isLong)));
        }

        return new Eval(gates, extras, window);
    }

    /** Route a condition into the gates, the score, or nowhere, per its configured mode. */
    private void place(List<Check> gates, List<Check> extras,
                       TrendMode mode, String name, boolean passed) {
        switch (mode) {
            case GATE -> gates.add(new Check(name, passed));
            case SCORED -> extras.add(new Check(name, passed));
            case OFF -> { /* not evaluated */ }
        }
    }

    /** EMA sloping the right way over {@code bars}, read at index {@code j}. */
    private boolean emaSloping(double[] ema, int j, int bars, boolean isLong) {
        int back = j - bars;
        if (back < 0 || isNaN(ema[j]) || isNaN(ema[back])) {
            return false;
        }
        return isLong ? ema[j] > ema[back] : ema[j] < ema[back];
    }

    /**
     * Pick the dot pair that anchors the trendline, and measure the three mandatory
     * gates across it.
     *
     * The anchor defines the window, so priceTrend and moneyFlow are measured from the
     * anchor dot to the latest dot rather than over a fixed lookback — all three gates
     * then describe the same stretch of chart.
     *
     * With {@link DotAnchor#ANY} every earlier dot is a candidate and the first one where
     * all three gates hold <em>together</em> wins; if none does, the closest candidate is
     * returned so the reason string still explains what failed.
     */
    private DotWindow selectWindow(CipherB.Series s, double[] ema, int i, boolean isLong) {
        boolean[] dots = isLong ? s.buyDot() : s.sellDot();

        int latestIdx = -1;
        List<Integer> earlier = new ArrayList<>();
        for (int k = i; k >= 0 && k > i - props.dotLookbackBars(); k--) {
            if (!dots[k]) {
                continue;
            }
            if (latestIdx < 0) {
                latestIdx = k;
            } else {
                earlier.add(k);   // descending: index 0 is the dot just before the latest
            }
        }

        // No pair means no window: the trendline gate fails, and the other two fall back
        // to their fixed lookbacks so the diagnostics still say something useful.
        if (latestIdx < 0 || earlier.isEmpty() || isNaN(s.wt2()[latestIdx])) {
            return new DotWindow(-1, latestIdx, false,
                    priceTrend(s.close(), ema, i, isLong),
                    rising(s.moneyFlow(), i, props.mfLookback(), isLong));
        }

        List<Integer> candidates = switch (props.dotAnchor()) {
            case PREVIOUS -> List.of(earlier.get(0));
            case LOWEST -> {
                int extreme = -1;
                for (int idx : earlier) {
                    if (isNaN(s.wt2()[idx])) {
                        continue;
                    }
                    if (extreme < 0
                            || (isLong ? s.wt2()[idx] < s.wt2()[extreme] : s.wt2()[idx] > s.wt2()[extreme])) {
                        extreme = idx;
                    }
                }
                yield extreme < 0 ? List.of() : List.of(extreme);
            }
            case ANY -> earlier;
        };

        // Only conditions that actually gate should constrain which anchor is chosen;
        // otherwise an ignored trend check would still veto otherwise-valid anchors.
        boolean trendGates = props.trendMode() == TrendMode.GATE;

        DotWindow best = null;
        for (int anchorIdx : candidates) {
            DotWindow w = new DotWindow(
                    anchorIdx, latestIdx,
                    slopeQualifies(s, latestIdx, anchorIdx, isLong),
                    priceTrendAcross(s.close(), ema, anchorIdx, latestIdx, i, isLong),
                    risingAcross(s.moneyFlow(), anchorIdx, latestIdx, isLong));

            boolean satisfied = w.trendline() && w.moneyFlow() && (!trendGates || w.priceTrend());
            if (satisfied) {
                return w;
            }
            if (best == null || w.passed() > best.passed()) {
                best = w;
            }
        }

        return best != null ? best : new DotWindow(-1, latestIdx, false,
                priceTrend(s.close(), ema, i, isLong),
                rising(s.moneyFlow(), i, props.mfLookback(), isLong));
    }

    /**
     * Trend EMA sloping the right way across the dot window, with price on the correct
     * side of it at the current bar (the bar the trade would actually be taken on).
     */
    private boolean priceTrendAcross(double[] close, double[] ema,
                                     int anchorIdx, int latestIdx, int i, boolean isLong) {
        if (isNaN(ema[i])) {
            return false;
        }

        boolean emaTurning;
        if (props.trendWindow() == TrendWindow.MACRO) {
            // Fixed horizon at the signal bar: independent of how far apart the dots fell.
            int back = i - props.macroTrendBars();
            if (back < 0 || isNaN(ema[back])) {
                return false;
            }
            emaTurning = isLong ? ema[i] > ema[back] : ema[i] < ema[back];
        } else {
            if (isNaN(ema[anchorIdx]) || isNaN(ema[latestIdx])) {
                return false;
            }
            emaTurning = isLong
                    ? ema[latestIdx] > ema[anchorIdx]
                    : ema[latestIdx] < ema[anchorIdx];
        }

        if (!props.trendRequirePriceSide()) {
            return emaTurning;
        }
        // Measured at 8.3% of dots against a ~49% baseline: buying a flush puts price
        // below its own averages by construction, at any horizon.
        boolean priceSide = isLong ? close[i] > ema[i] : close[i] < ema[i];
        return emaTurning && priceSide;
    }

    /** A series rising (long) or falling (short) across the dot window. */
    private boolean risingAcross(double[] series, int anchorIdx, int latestIdx, boolean isLong) {
        if (isNaN(series[anchorIdx]) || isNaN(series[latestIdx])) {
            return false;
        }
        return isLong
                ? series[latestIdx] > series[anchorIdx]
                : series[latestIdx] < series[anchorIdx];
    }

    /** Money flow (or any series) rising over the lookback for longs, falling for shorts. */
    private boolean rising(double[] series, int i, int lookback, boolean isLong) {
        int back = i - lookback;
        if (back < 0 || isNaN(series[i]) || isNaN(series[back])) {
            return false;
        }
        return isLong ? series[i] > series[back] : series[i] < series[back];
    }

    /**
     * Long: RSI inside the recovery band and turning up.
     * Short: RSI inside the rolling-over band and turning down.
     */
    private boolean rsiBand(double[] rsi, int i, boolean isLong) {
        if (i < 1 || isNaN(rsi[i]) || isNaN(rsi[i - 1])) {
            return false;
        }
        double v = rsi[i];
        if (isLong) {
            return v >= props.rsiLongMin() && v <= props.rsiLongMax() && v > rsi[i - 1];
        }
        return v >= props.rsiShortMin() && v <= props.rsiShortMax() && v < rsi[i - 1];
    }

    /** wtVwap = wt1 - wt2; above zero means WaveTrend momentum has turned up. */
    private boolean momentumFlipped(double[] wtVwap, int i, boolean isLong) {
        if (isNaN(wtVwap[i])) {
            return false;
        }
        return isLong ? wtVwap[i] > 0 : wtVwap[i] < 0;
    }

    /**
     * Trendline across the oscillator dots: draw the line from an earlier dot to the most
     * recent one, in (barIndex, wt2) space, and require it to slope the right way.
     *
     * <pre>
     *   slope = (wt2[latest] - wt2[anchor]) / (latest - anchor)   // per bar
     * </pre>
     *
     * Long needs a rising line (higher low at each green dot), short a falling one.
     * Which earlier dot anchors the line is set by {@code dot-anchor}; {@code dot-min-slope}
     * can demand a steeper line than merely non-flat.
     */
    /** Slope of the line from an earlier dot to the latest, tested against the minimum. */
    private boolean slopeQualifies(CipherB.Series s, int latestIdx, int anchorIdx, boolean isLong) {
        double latest = s.wt2()[latestIdx];
        double anchor = s.wt2()[anchorIdx];
        if (isNaN(latest) || isNaN(anchor) || latestIdx == anchorIdx) {
            return false;
        }
        double slope = (latest - anchor) / (latestIdx - anchorIdx);
        return isLong ? slope > props.dotMinSlope() : slope < -props.dotMinSlope();
    }

    /**
     * Price on the right side of the trend EMA, with the EMA sloping that way over
     * {@code trend-slope-bars}. Measuring the slope over several bars rather than one
     * keeps the gate from flipping on a single noisy bar.
     */
    private boolean priceTrend(double[] close, double[] ema, int i, boolean isLong) {
        int back = i - Math.max(1, props.trendSlopeBars());
        if (back < 0 || isNaN(ema[i]) || isNaN(ema[back])) {
            return false;
        }
        boolean emaTurning = isLong ? ema[i] > ema[back] : ema[i] < ema[back];
        boolean priceSide = isLong ? close[i] > ema[i] : close[i] < ema[i];
        return emaTurning && priceSide;
    }

    /** True if a dot printed within the last {@code maxAge} bars (inclusive of the current bar). */
    private boolean recentDot(boolean[] dots, int i, int maxAge) {
        for (int k = i; k >= 0 && k > i - maxAge; k--) {
            if (dots[k]) {
                return true;
            }
        }
        return false;
    }

    /**
     * A recent dot that also clears the entry filters: a deeper oversold threshold than
     * Cipher B's default if configured, and optionally a gold buy or a divergence.
     *
     * These select for <em>more extreme</em> conditions rather than healthier ones,
     * which is the direction the measurements point: average win fell from 0.97% to
     * 0.70% when a higher-timeframe uptrend was required.
     */
    private boolean recentQualifyingDot(CipherB.Series s, int i, boolean isLong) {
        boolean[] dots = isLong ? s.buyDot() : s.sellDot();
        for (int k = i; k >= 0 && k > i - props.dotMaxAgeBars(); k--) {
            if (!dots[k]) {
                continue;
            }
            // Deeper capitulation than the -53 default, when configured.
            double level = props.dotOversoldLevel();
            boolean deepEnough = isNaN(s.wt2()[k])
                    || (isLong ? s.wt2()[k] <= level : s.wt2()[k] >= -level);
            if (!deepEnough) {
                continue;
            }
            if (props.requireGoldBuy() && !(isLong && s.goldBuy()[k])) {
                continue;
            }
            if (props.requireDivergence() && !(isLong ? s.bullDiv()[k] : s.bearDiv()[k])) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Signal-based exits: the opposite Cipher B dot. Each value depends only on bars up
     * to its own index, so the backtester can read it during replay safely.
     */
    @Override
    public boolean[] exitSignals(ChartData chart, Signal side) {
        if (props.exitMode() != ExitMode.OPPOSITE_DOT) {
            return null;
        }
        CipherB.Series s = CipherB.computeSeries(chart.candles());
        return side == Signal.LONG ? s.sellDot() : s.buyDot();
    }

    /**
     * Place the stop just beyond the nearest support/resistance level and the target just
     * short of the next level in the trade's direction.
     *
     * Where no level is available the old behaviour stands in: the recent swing for the
     * stop, and the configured reward:risk multiple for the target. The reason string
     * records which source was used and the resulting R:R, since a level-based target can
     * produce a far worse ratio than the fixed multiple did.
     */
    private AnalysisResult withLevels(String symbol, String interval, Signal signal,
                                      CipherB.Series s, int i, String reason,
                                      SupportResistance.Result sr) {
        boolean isLong = signal == Signal.LONG;
        double entry = s.close()[i];

        // ATR modes size both sides to volatility, so the stop sits outside normal noise
        // and the target is not capped by whatever level happens to sit just overhead.
        if (props.exitMode() != ExitMode.LEVELS) {
            double atr = s.atr()[i];
            if (isNaN(atr) || atr <= 0) {
                return none(symbol, interval, reason + " — rejected: ATR unavailable");
            }
            double stop = isLong ? entry - props.atrStopMult() * atr : entry + props.atrStopMult() * atr;
            double tgt = isLong ? entry + props.atrTargetMult() * atr : entry - props.atrTargetMult() * atr;
            String note = String.format(" stop=%.2fxATR target=%.2fxATR atr=%.4f rr=%.2f",
                    props.atrStopMult(), props.atrTargetMult(), atr,
                    props.atrTargetMult() / props.atrStopMult());
            return new AnalysisResult(symbol, interval, signal, stop, tgt, reason + note);
        }

        double stopBuffer = entry * srProps.stopBufferPct() / 100.0;
        double targetBuffer = entry * srProps.targetBufferPct() / 100.0;

        // Stop: beyond the level guarding the trade, else the recent swing.
        Optional<SupportResistance.Level> stopLevel =
                isLong ? sr.nearestBelow(entry) : sr.nearestAbove(entry);
        String stopSource;
        double stopLoss;
        if (stopLevel.isPresent()) {
            SupportResistance.Level level = stopLevel.get();
            stopLoss = isLong ? level.price() - stopBuffer : level.price() + stopBuffer;
            stopSource = String.format("level %.4f x%d", level.price(), level.touches());
        } else {
            stopLoss = swing(s, i, isLong);
            stopSource = "swing";
        }

        double risk = isLong ? entry - stopLoss : stopLoss - entry;
        if (risk <= 0) {
            return none(symbol, interval, reason + " — rejected: non-positive risk at the stop");
        }

        // Target: short of the next level ahead, else the fixed reward:risk multiple.
        Optional<SupportResistance.Level> targetLevel =
                isLong ? sr.nearestAbove(entry) : sr.nearestBelow(entry);
        String targetSource;
        double target;
        if (targetLevel.isPresent()) {
            SupportResistance.Level level = targetLevel.get();
            target = isLong ? level.price() - targetBuffer : level.price() + targetBuffer;
            targetSource = String.format("level %.4f x%d", level.price(), level.touches());
        } else {
            target = isLong ? entry + props.rewardRisk() * risk : entry - props.rewardRisk() * risk;
            targetSource = "fixed " + props.rewardRisk() + "R";
        }

        double reward = isLong ? target - entry : entry - target;
        if (reward <= 0) {
            return none(symbol, interval, reason
                    + " — rejected: target level (" + targetSource + ") is not beyond entry");
        }

        String levels = String.format(" stop=%s target=%s rr=%.2f",
                stopSource, targetSource, reward / risk);
        return new AnalysisResult(symbol, interval, signal, stopLoss, target, reason + levels);
    }

    /** Extreme of the recent swing: the low for longs, the high for shorts. */
    private double swing(CipherB.Series s, int i, boolean isLong) {
        int from = Math.max(0, i - props.swingLookback() + 1);
        double extreme = isLong ? Double.MAX_VALUE : -Double.MAX_VALUE;
        for (int k = from; k <= i; k++) {
            extreme = isLong ? Math.min(extreme, s.low()[k]) : Math.max(extreme, s.high()[k]);
        }
        return extreme;
    }

    private String describe(String side, Eval eval, int available) {
        DotWindow w = eval.window();
        String window = w.valid()
                ? " window=" + w.spanBars() + "bars"
                : " window=none";
        return side + window
                + " gates{" + names(eval.gates(), true) + " | failed: " + names(eval.gates(), false) + "}"
                + " score " + eval.score() + "/" + available
                + " passed=[" + names(eval.extras(), true) + "]"
                + " failed=[" + names(eval.extras(), false) + "]";
    }

    /** Comma-separated names of the checks matching {@code passed}. */
    private String names(List<Check> checks, boolean passed) {
        StringBuilder sb = new StringBuilder();
        for (Check c : checks) {
            if (c.passed() != passed) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(c.name());
        }
        return sb.toString();
    }

    private boolean isNaN(double v) {
        return Double.isNaN(v);
    }

    private AnalysisResult none(String symbol, String interval, String reason) {
        return new AnalysisResult(symbol, interval, Signal.NONE, 0, 0, reason);
    }
}
