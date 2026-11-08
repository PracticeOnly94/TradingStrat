package com.tradingstrat.analysis;

import com.tradingstrat.indicator.CipherB;
import com.tradingstrat.indicator.Ta;
import com.tradingstrat.model.ChartData;
import com.tradingstrat.ta4j.BarSeriesAdapter;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.supertrend.SuperTrendIndicator;
import org.ta4j.core.indicators.supportresistance.VolumeProfileKDEIndicator;
import org.ta4j.core.indicators.volume.MoneyFlowIndexIndicator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Forward returns conditional on an indicator reading.
 *
 * <p>This is the inverse of {@link ExtremeProfiler}, and the version that bears on
 * trading. Knowing that RSI was low at most bottoms says nothing about whether buying
 * low RSI pays — bottoms are a fraction of a percent of bars, so a condition can be
 * present at nearly every bottom and still be a losing trade because it also occurs
 * constantly elsewhere. Here the question runs the other way: given the condition, what
 * did price do next?
 *
 * <p>Everything is measured against the same forward windows on every bar, so the
 * baseline row is directly comparable and captures the market's own drift.
 */
@Component
public class ConditionProfiler {

    /** Forward windows, in bars. On 1h data: half a day, a day, two days. */
    public static final int[] HORIZONS = {12, 24, 48};

    /** Row layout: forward returns as percentages, then the same in ATRs. */
    public static final int COLS = HORIZONS.length * 2;

    /** Per-condition rows of forward outcomes. */
    public record Samples(Map<String, List<double[]>> byCondition, int bars) {}

    public Samples profile(ChartData chart) {
        CipherB.Series s = CipherB.computeSeries(chart.candles());
        int n = s.length();

        // ta4j indicators for the conditions we did not build ourselves. SuperTrend and
        // MFI are both causal, and the volume profile is given an explicit lookback so it
        // only ever sees bars at or before the one being evaluated.
        BarSeries series = BarSeriesAdapter.toBarSeries(chart);
        SuperTrendIndicator superTrend = new SuperTrendIndicator(series, 10, 3.0);
        MoneyFlowIndexIndicator mfi = new MoneyFlowIndexIndicator(series, 14);

        // Volume profile with an explicit 200-bar lookback: bounded so it stays causal
        // (it never sees past the evaluated bar) and cheap enough to call per bar.
        VolumeProfileKDEIndicator poc = new VolumeProfileKDEIndicator(
                new ClosePriceIndicator(series), new VolumeIndicator(series, 1), 200,
                series.numFactory().numOf(series.getBar(series.getBarCount() - 1)
                        .getClosePrice().doubleValue() * 0.0025));

        // Range boundaries: where price sits inside its recent envelope, and how wide
        // that envelope is relative to volatility.
        double[] rangeHigh = Ta.highest(s.high(), 100);
        double[] rangeLow = Ta.lowest(s.low(), 100);

        // Drawdown from the recent peak, and whether the fall has stopped making new lows.
        double[] peak = Ta.highest(s.close(), 100);
        double[] recentLow = Ta.lowest(s.low(), 10);

        // Volatility regime: current ATR against its own recent average, so "calm" and
        // "violent" are relative to the instrument rather than an absolute threshold.
        double[] atrPct = new double[n];
        for (int i = 0; i < n; i++) {
            atrPct[i] = Double.isNaN(s.atr()[i]) ? Double.NaN : s.atr()[i] / s.close()[i] * 100.0;
        }
        double[] atrPctAvg = Ta.sma(atrPct, 100);

        // Calendar: the one axis in this study that owes nothing to price.
        int[] hour = new int[n];
        int[] dayOfWeek = new int[n];
        for (int i = 0; i < n; i++) {
            java.time.ZonedDateTime t = chart.candles().get(i).timestamp()
                    .atZone(java.time.ZoneOffset.UTC);
            hour[i] = t.getHour();
            dayOfWeek[i] = t.getDayOfWeek().getValue();   // 1 = Monday
        }

        double[] close = s.close();
        double[] atr = s.atr();
        double[] ema50 = Ta.ema(close, 50);
        double[] ema200 = Ta.ema(close, 200);

        double[] volume = new double[n];
        for (int i = 0; i < n; i++) {
            volume[i] = chart.candles().get(i).volume();
        }
        double[] volAvg = Ta.sma(volume, 50);

        // Confirmed pivot lows/highs, used as "price is at a prior structural level".
        // A pivot is confirmed two bars after it forms, so reading it at bar i is causal.
        double[] pivotLows = Ta.pivotLow(s.low(), 2, 2);
        double[] pivotHighs = Ta.pivotHigh(s.high(), 2, 2);

        Map<String, List<double[]>> out = new LinkedHashMap<>();
        int maxHorizon = HORIZONS[HORIZONS.length - 1];
        int bars = 0;

        for (int i = 250; i < n - maxHorizon; i++) {
            if (Double.isNaN(atr[i]) || atr[i] <= 0 || Double.isNaN(ema50[i]) || Double.isNaN(ema200[i])) {
                continue;
            }
            bars++;

            double[] forward = new double[COLS];
            for (int h = 0; h < HORIZONS.length; h++) {
                double change = close[i + HORIZONS[h]] - close[i];
                forward[h] = change / close[i] * 100.0;
                forward[HORIZONS.length + h] = change / atr[i];
            }

            for (String condition : conditions(s, i, ema50, ema200, atr, volume, volAvg,
                    superTrend, mfi, pivotLows, pivotHighs, poc, rangeHigh, rangeLow,
                    peak, recentLow, atrPct, atrPctAvg, hour, dayOfWeek)) {
                out.computeIfAbsent(condition, k -> new ArrayList<>()).add(forward);
            }
        }

        return new Samples(out, bars);
    }

    /** Every condition true at bar {@code i}, including the always-true baseline. */
    private List<String> conditions(CipherB.Series s, int i, double[] ema50, double[] ema200,
                                    double[] atr, double[] volume, double[] volAvg,
                                    SuperTrendIndicator superTrend, MoneyFlowIndexIndicator mfi,
                                    double[] pivotLows, double[] pivotHighs,
                                    VolumeProfileKDEIndicator poc,
                                    double[] rangeHigh, double[] rangeLow,
                                    double[] peak, double[] recentLow,
                                    double[] atrPct, double[] atrPctAvg,
                                    int[] hour, int[] dayOfWeek) {
        List<String> on = new ArrayList<>();
        on.add("ALL_BARS");

        double rsi = s.rsi()[i];
        double wt2 = s.wt2()[i];
        double stretch50 = (s.close()[i] - ema50[i]) / atr[i];
        double volRatio = Double.isNaN(volAvg[i]) || volAvg[i] <= 0 ? 1 : volume[i] / volAvg[i];
        boolean bigVolume = volRatio >= 1.5;
        boolean stretchedDown = stretch50 <= -2.0;
        boolean stretchedUp = stretch50 >= 2.0;
        boolean rsiLow = rsi < 30;
        boolean rsiHigh = rsi > 70;

        // --- single conditions, long side ---
        if (rsiLow) on.add("rsi<30");
        if (rsi < 25) on.add("rsi<25");
        if (wt2 <= -53) on.add("wt2<=-53");
        if (wt2 <= -60) on.add("wt2<=-60");
        if (wt2 <= -75) on.add("wt2<=-75");
        if (s.buyDot()[i]) on.add("buyDot");
        if (s.bullDiv()[i]) on.add("bullDiv");
        if (s.goldBuy()[i]) on.add("goldBuy");
        if (stretchedDown) on.add("stretch<=-2ATR");
        if (stretch50 <= -3.0) on.add("stretch<=-3ATR");

        // --- single conditions, short side ---
        if (rsiHigh) on.add("rsi>70");
        if (wt2 >= 53) on.add("wt2>=53");
        if (wt2 >= 60) on.add("wt2>=60");
        if (s.sellDot()[i]) on.add("sellDot");
        if (s.bearDiv()[i]) on.add("bearDiv");
        if (stretchedUp) on.add("stretch>=+2ATR");

        // --- volume, which marks extremes without saying which kind ---
        if (bigVolume) on.add("volume>=1.5x");

        // --- pairs, to see whether combining adds anything ---
        if (rsiLow && bigVolume) on.add("rsi<30 + vol");
        if (stretchedDown && bigVolume) on.add("stretch-2 + vol");
        if (rsiLow && stretchedDown) on.add("rsi<30 + stretch-2");
        if (s.buyDot()[i] && rsiLow) on.add("buyDot + rsi<30");
        if (s.buyDot()[i] && stretchedDown) on.add("buyDot + stretch-2");
        if (s.buyDot()[i] && s.bullDiv()[i]) on.add("buyDot + bullDiv");
        if (rsiLow && stretchedDown && bigVolume) on.add("rsi<30 + stretch + vol");
        if (rsiHigh && stretchedUp) on.add("rsi>70 + stretch+2");
        if (s.sellDot()[i] && rsiHigh) on.add("sellDot + rsi>70");

        // --- regime split on the best long condition, to test whether trend matters ---
        boolean bull = i >= 200 && !Double.isNaN(ema200[i - 200]) && ema200[i] > ema200[i - 200];
        if (rsiLow && bull) on.add("rsi<30 in BULL");
        if (rsiLow && !bull) on.add("rsi<30 in BEAR");
        if (stretchedDown && bull) on.add("stretch-2 in BULL");
        if (stretchedDown && !bull) on.add("stretch-2 in BEAR");

        // ---------------------------------------------------------------
        // SuperTrend / true MFI / volume-profile proximity.
        // ---------------------------------------------------------------
        boolean stUp = superTrend.isUpTrend(i);
        boolean stFlipped = superTrend.trendChanged(i);
        if (stUp) on.add("st:bull");
        if (stFlipped && stUp) on.add("st:flipUp");
        if (stFlipped && !stUp) on.add("st:flipDown");

        double mfiValue = mfi.getValue(i).doubleValue();
        boolean mfiLow = mfiValue < 20;
        boolean mfiSoft = mfiValue < 30;
        if (mfiLow) on.add("mfi<20");
        if (mfiSoft) on.add("mfi<30");
        if (mfiValue > 80) on.add("mfi>80");

        // "Price is at a prior structural level": within half an ATR of a confirmed
        // pivot in the recent past. This is the computable version of the strategy's
        // "near a Value Area Low / POC / high volume node".
        boolean atSupport = nearPivot(pivotLows, s.close()[i], i, atr[i]);
        boolean atResistance = nearPivot(pivotHighs, s.close()[i], i, atr[i]);
        if (atSupport) on.add("atSupport");
        if (atResistance) on.add("atResistance");

        // --- the pieces combined, building up to the full stack ---
        if (stFlipped && stUp && bull) on.add("st:flipUp in BULL");
        if (stFlipped && stUp && bigVolume) on.add("st:flipUp + vol");
        if (stFlipped && stUp && mfiSoft) on.add("st:flipUp + mfi<30");
        if (stFlipped && stUp && atSupport) on.add("st:flipUp + atSupport");
        if (mfiSoft && atSupport) on.add("mfi<30 + atSupport");
        if (stFlipped && stUp && mfiSoft && bull) on.add("st:flip + mfi + BULL");

        // The strategy as described: 4h-style bias, SuperTrend flip, MFI recovering,
        // price at a level, and a volume spike — all at once.
        if (stFlipped && stUp && mfiSoft && atSupport && bigVolume && bull) {
            on.add("FULL STACK");
        }
        // Same without the volume spike, since volume measured as directionless.
        if (stFlipped && stUp && mfiSoft && atSupport && bull) {
            on.add("STACK no-vol");
        }

        // ---------------------------------------------------------------
        // Range structure and volume profile.
        //
        // A different premise from everything above: rather than predicting a reversal,
        // these ask whether price is at the edge of an established envelope, and whether
        // it is stretched away from where volume actually traded.
        // ---------------------------------------------------------------
        boolean atRangeLowFlag = false;
        if (!Double.isNaN(rangeHigh[i]) && !Double.isNaN(rangeLow[i]) && rangeHigh[i] > rangeLow[i]) {
            double close = s.close()[i];
            double pctile = (close - rangeLow[i]) / (rangeHigh[i] - rangeLow[i]) * 100.0;
            double widthAtr = (rangeHigh[i] - rangeLow[i]) / atr[i];

            boolean atRangeLow = pctile <= 20;
            atRangeLowFlag = atRangeLow;
            boolean atRangeHigh = pctile >= 80;
            // Width relative to volatility: a tight envelope is consolidation, a wide
            // one usually means a trend has already run.
            boolean narrow = widthAtr < 10;
            boolean wide = widthAtr > 20;

            if (atRangeLow) on.add("rangeLow20");
            if (atRangeHigh) on.add("rangeHigh80");
            if (narrow) on.add("narrowRange");
            if (wide) on.add("wideRange");
            if (atRangeLow && narrow) on.add("rangeLow + narrow");
            if (atRangeLow && wide) on.add("rangeLow + wide");
            if (atRangeHigh && narrow) on.add("rangeHigh + narrow");
            if (atRangeLow && rsiLow) on.add("rangeLow + rsi<30");
            if (atRangeLow && bigVolume) on.add("rangeLow + vol");
        }

        double pocPrice = poc.getModePrice(i).doubleValue();
        if (!Double.isNaN(pocPrice) && pocPrice > 0) {
            double distAtr = (s.close()[i] - pocPrice) / atr[i];
            if (distAtr <= -2) on.add("belowPOC>2atr");
            if (distAtr >= 2) on.add("abovePOC>2atr");
            if (Math.abs(distAtr) <= 0.5) on.add("atPOC");
            if (distAtr <= -2 && rsiLow) on.add("belowPOC + rsi<30");
        }

        // ---------------------------------------------------------------
        // Drawdown depth and patience.
        //
        // Not "is an oscillator low" but "how far has this actually fallen, and has it
        // stopped falling". Our oversold signals fired at the start of declines and were
        // run over; this asks whether magnitude plus stabilisation does better.
        // ---------------------------------------------------------------
        if (!Double.isNaN(peak[i]) && peak[i] > 0 && !Double.isNaN(recentLow[i])) {
            double drawdown = (s.close()[i] - peak[i]) / peak[i] * 100.0;
            // No new 10-bar low on this bar: the fall has paused rather than continuing.
            boolean stabilised = s.low()[i] > recentLow[i];

            if (drawdown <= -10) on.add("dd>10%");
            if (drawdown <= -20) on.add("dd>20%");
            if (drawdown <= -30) on.add("dd>30%");
            if (stabilised) on.add("stabilised");
            if (drawdown <= -20 && stabilised) on.add("dd>20% + stabilised");
            if (drawdown <= -30 && stabilised) on.add("dd>30% + stabilised");
        }

        // Volatility regime, relative to the instrument's own recent norm.
        boolean violent = false;
        boolean calm = false;
        if (!Double.isNaN(atrPct[i]) && !Double.isNaN(atrPctAvg[i]) && atrPctAvg[i] > 0) {
            calm = atrPct[i] < atrPctAvg[i] * 0.8;
            violent = atrPct[i] > atrPctAvg[i] * 1.3;
            if (calm) on.add("calmVol");
            if (violent) on.add("violentVol");
            if (rsiLow && calm) on.add("rsi<30 + calm");
            if (rsiLow && violent) on.add("rsi<30 + violent");
        }

        // ---------------------------------------------------------------
        // Three-way market regime.
        //
        // The binary split treated everything that was not rising as "bear", lumping
        // genuine downtrends together with sideways markets. Mean reversion and trend
        // behave differently in those two, so a flat band is separated out to let each
        // candidate be routed to the regime it actually suits.
        // ---------------------------------------------------------------
        String regime = "NEUT";
        if (i >= 200 && !Double.isNaN(ema200[i]) && !Double.isNaN(ema200[i - 200])
                && ema200[i - 200] != 0) {
            double slopePct = (ema200[i] - ema200[i - 200]) / Math.abs(ema200[i - 200]) * 100.0;
            regime = slopePct > 2 ? "BULL" : slopePct < -2 ? "BEAR" : "NEUT";
        }
        on.add("regime:" + regime);
        if (rsiLow) on.add("rsi<30 @" + regime);
        if (rsiLow && violent) on.add("rsi<30+viol @" + regime);
        if (rsiLow && calm) on.add("rsi<30+calm @" + regime);
        if (atRangeLowFlag && rsiLow) on.add("rangeLow+rsi @" + regime);
        if (wt2 >= 60) on.add("wt2>=60 @" + regime);
        if (s.buyDot()[i]) on.add("buyDot @" + regime);
        if (violent) on.add("violentVol @" + regime);

        // ---------------------------------------------------------------
        // Calendar. The only axis here that is not derived from price at all.
        // ---------------------------------------------------------------
        if (hour[i] < 8) on.add("sessionAsia");
        else if (hour[i] < 16) on.add("sessionEU");
        else on.add("sessionUS");

        if (dayOfWeek[i] >= 6) on.add("weekend");
        else on.add("weekday");
        if (dayOfWeek[i] == 1) on.add("monday");
        if (dayOfWeek[i] == 5) on.add("friday");

        return on;
    }

    /** True if price sits within half an ATR of a confirmed pivot in the last 200 bars. */
    private boolean nearPivot(double[] pivots, double close, int i, double atr) {
        double tolerance = 0.5 * atr;
        for (int k = Math.max(0, i - 200); k <= i; k++) {
            if (!Double.isNaN(pivots[k]) && Math.abs(close - pivots[k]) <= tolerance) {
                return true;
            }
        }
        return false;
    }
}
