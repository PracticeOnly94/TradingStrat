package com.tradingstrat.analysis;

import com.tradingstrat.indicator.CipherB;
import com.tradingstrat.indicator.Ta;
import com.tradingstrat.model.ChartData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Descriptive study of what indicators look like at real tops and bottoms.
 *
 * <p>This is the inverse of backtesting a rule. Rather than proposing an entry and
 * measuring its returns, it locates extremes that actually mattered and records the
 * indicator readings there, alongside the same readings across every bar. An indicator
 * only carries information if its distribution at extremes differs from its distribution
 * everywhere else — a value that is "low at bottoms" means nothing if it is equally low
 * half the time.
 *
 * <p><b>These labels use future data by construction</b> — a bar is only known to be a
 * bottom once the bars after it have printed. Nothing here is a tradeable signal; it is
 * a map of which indicators are worth building signals from.
 */
@Component
public class ExtremeProfiler {

    /**
     * @param pivotBars   bars either side that a bar must exceed to count as an extreme
     * @param forwardBars window in which the subsequent move is measured
     * @param minMoveAtr  the move must reach this many ATRs to count as significant,
     *                    which filters out the countless trivial wiggles
     */
    public record Params(int pivotBars, int forwardBars, double minMoveAtr) {
        public static Params defaults() {
            return new Params(24, 48, 3.0);
        }
    }

    /** Indicator samples, keyed by indicator name. */
    public record Samples(
            Map<String, List<Double>> atBottoms,
            Map<String, List<Double>> atTops,
            Map<String, List<Double>> baseline,
            int bottoms,
            int tops,
            int bars
    ) {}

    /** Profile one symbol. Values are scale-free so they pool across symbols. */
    public Samples profile(ChartData chart, Params p) {
        CipherB.Series s = CipherB.computeSeries(chart.candles());
        int n = s.length();

        double[] close = s.close();
        double[] high = s.high();
        double[] low = s.low();
        double[] ema50 = Ta.ema(close, 50);
        double[] ema200 = Ta.ema(close, 200);
        double[] atr = s.atr();

        double[] volume = new double[n];
        for (int i = 0; i < n; i++) {
            volume[i] = chart.candles().get(i).volume();
        }
        double[] volAvg = Ta.sma(volume, 50);

        Map<String, List<Double>> bottoms = new LinkedHashMap<>();
        Map<String, List<Double>> tops = new LinkedHashMap<>();
        Map<String, List<Double>> baseline = new LinkedHashMap<>();

        int bottomCount = 0;
        int topCount = 0;
        int barCount = 0;

        int from = 250;                       // indicator warm-up
        int to = n - p.forwardBars() - 1;     // room to measure the subsequent move

        for (int i = from; i < to; i++) {
            if (Double.isNaN(ema200[i]) || Double.isNaN(atr[i]) || atr[i] <= 0) {
                continue;
            }
            Map<String, Double> reading = read(s, i, ema50, ema200, atr, volume, volAvg);

            // Every bar contributes to the baseline the extremes are judged against.
            reading.forEach((k, v) -> baseline.computeIfAbsent(k, x -> new ArrayList<>()).add(v));
            barCount++;

            if (isBottom(low, high, i, p, atr[i])) {
                reading.forEach((k, v) -> bottoms.computeIfAbsent(k, x -> new ArrayList<>()).add(v));
                bottomCount++;
            }
            if (isTop(low, high, i, p, atr[i])) {
                reading.forEach((k, v) -> tops.computeIfAbsent(k, x -> new ArrayList<>()).add(v));
                topCount++;
            }
        }

        return new Samples(bottoms, tops, baseline, bottomCount, topCount, barCount);
    }

    /**
     * A bottom: the lowest low within +/- pivotBars, from which price then rose at least
     * minMoveAtr ATRs. The magnitude filter is what separates a bottom worth studying
     * from the hundreds of meaningless local dips in any series.
     */
    private boolean isBottom(double[] low, double[] high, int i, Params p, double atr) {
        for (int k = i - p.pivotBars(); k <= i + p.pivotBars(); k++) {
            if (k < 0 || k >= low.length || k == i) {
                continue;
            }
            if (low[k] < low[i]) {
                return false;
            }
        }
        double best = low[i];
        for (int k = i + 1; k <= i + p.forwardBars() && k < high.length; k++) {
            best = Math.max(best, high[k]);
        }
        return (best - low[i]) >= p.minMoveAtr() * atr;
    }

    /** A top: mirror of {@link #isBottom}. */
    private boolean isTop(double[] low, double[] high, int i, Params p, double atr) {
        for (int k = i - p.pivotBars(); k <= i + p.pivotBars(); k++) {
            if (k < 0 || k >= high.length || k == i) {
                continue;
            }
            if (high[k] > high[i]) {
                return false;
            }
        }
        double worst = high[i];
        for (int k = i + 1; k <= i + p.forwardBars() && k < low.length; k++) {
            worst = Math.min(worst, low[k]);
        }
        return (high[i] - worst) >= p.minMoveAtr() * atr;
    }

    /**
     * Indicator readings at one bar. All values are scale-free — percentages, oscillator
     * levels, ATR multiples or ratios — so they can be pooled across symbols priced from
     * cents to tens of thousands.
     */
    private Map<String, Double> read(CipherB.Series s, int i, double[] ema50, double[] ema200,
                                     double[] atr, double[] volume, double[] volAvg) {
        Map<String, Double> m = new LinkedHashMap<>();
        double close = s.close()[i];

        m.put("wt2", s.wt2()[i]);
        m.put("wtVwap", s.wtVwap()[i]);
        m.put("rsi", s.rsi()[i]);
        m.put("stochK", s.stochK()[i]);
        m.put("moneyFlow", s.moneyFlow()[i]);

        // Distance from the moving averages, in ATRs: how stretched price is, normalised
        // for volatility so a quiet market and a wild one are comparable.
        m.put("atrFromEma50", Double.isNaN(ema50[i]) ? Double.NaN : (close - ema50[i]) / atr[i]);
        m.put("atrFromEma200", Double.isNaN(ema200[i]) ? Double.NaN : (close - ema200[i]) / atr[i]);
        m.put("atrPct", atr[i] / close * 100.0);

        m.put("rangePctile", rangePercentile(s, i, 100));
        m.put("volumeRatio", Double.isNaN(volAvg[i]) || volAvg[i] <= 0 ? Double.NaN : volume[i] / volAvg[i]);

        // Booleans as 0/1 so they can be averaged into rates.
        m.put("b_buyDot", near(s.buyDot(), i, 3) ? 1.0 : 0.0);
        m.put("b_sellDot", near(s.sellDot(), i, 3) ? 1.0 : 0.0);
        m.put("b_bullDiv", near(s.bullDiv(), i, 3) ? 1.0 : 0.0);
        m.put("b_bearDiv", near(s.bearDiv(), i, 3) ? 1.0 : 0.0);
        m.put("b_goldBuy", near(s.goldBuy(), i, 3) ? 1.0 : 0.0);
        m.put("b_wt2_below_60", s.wt2()[i] <= -60 ? 1.0 : 0.0);
        m.put("b_wt2_above_60", s.wt2()[i] >= 60 ? 1.0 : 0.0);
        m.put("b_rsi_below_30", s.rsi()[i] < 30 ? 1.0 : 0.0);
        m.put("b_rsi_above_70", s.rsi()[i] > 70 ? 1.0 : 0.0);
        m.put("b_ema200_rising", !Double.isNaN(ema200[i]) && i >= 200
                && !Double.isNaN(ema200[i - 200]) && ema200[i] > ema200[i - 200] ? 1.0 : 0.0);
        return m;
    }

    /** Where the close sits in the recent high-low range, 0 (low) to 100 (high). */
    private double rangePercentile(CipherB.Series s, int i, int lookback) {
        int from = Math.max(0, i - lookback + 1);
        double hi = -Double.MAX_VALUE;
        double lo = Double.MAX_VALUE;
        for (int k = from; k <= i; k++) {
            hi = Math.max(hi, s.high()[k]);
            lo = Math.min(lo, s.low()[k]);
        }
        return hi == lo ? 50.0 : (s.close()[i] - lo) / (hi - lo) * 100.0;
    }

    private boolean near(boolean[] flags, int i, int window) {
        for (int k = Math.max(0, i - window); k <= Math.min(flags.length - 1, i + window); k++) {
            if (flags[k]) {
                return true;
            }
        }
        return false;
    }
}
