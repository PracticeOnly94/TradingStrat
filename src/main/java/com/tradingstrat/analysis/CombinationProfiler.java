package com.tradingstrat.analysis;

import com.tradingstrat.indicator.CipherB;
import com.tradingstrat.indicator.Ta;
import com.tradingstrat.model.Candle;
import com.tradingstrat.model.ChartData;
import com.tradingstrat.ta4j.BarSeriesAdapter;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.supertrend.SuperTrendIndicator;
import org.ta4j.core.indicators.volume.MoneyFlowIndexIndicator;

import java.util.ArrayList;
import java.util.List;

/**
 * Exhaustive search over combinations of indicator conditions, split by market regime.
 *
 * <p><b>This is a data-mining machine, and that is the danger.</b> Every combination of
 * fifteen primitives up to size four is ~1,900 hypotheses per regime; at a 5% false
 * positive rate that is ~95 spurious "edges" before any real one appears, and demanding
 * agreement across three years only filters to one in eight by chance. The single
 * control that survives this arithmetic is an untouched holdout: search on one set of
 * years, test survivors on years the search never saw. Nothing from the search half is
 * evidence on its own.
 *
 * <p>Bull and bear bars are scored separately, each against its own baseline. Pooling
 * them would be meaningless — baseline 48-bar returns ranged from −2.01% in 2022 to
 * +2.11% in 2024, so a condition can look strong purely by occurring in the better
 * regime. Regime is therefore a partition, not a condition.
 *
 * <p>Conditions live as bits in an int per bar, so testing a combination is one AND,
 * which keeps ~1,900 hypotheses across ~40,000 bars to a few seconds.
 */
@Component
public class CombinationProfiler {

    /** Bit position determines the mask; order is fixed and must not be reshuffled. */
    public static final String[] PRIMITIVES = {
            "rsi<30", "rsi>70",
            "wt2<=-53", "wt2>=60",
            "buyDot", "sellDot",
            "bullDiv", "bearDiv",
            "mfi<20", "mfi>80",
            "stretch<=-2", "stretch>=+2",
            "bigVolume",
            "stBull", "stFlipUp",
            // Cipher B's own overbought level, the exact mirror of wt2<=-53. Added so the
            // short side can be tested symmetrically rather than against the stricter 60.
            "wt2>=53"
    };

    /** Forward window the search scores on, in bars. */
    public static final int HORIZON = 48;

    /** Outcomes for every combination within one regime. */
    public record RegimeStats(
            long[] counts,
            double[] sums,
            long[] positives,
            double baselineMean,
            int bars
    ) {
        public double mean(int idx) {
            return counts[idx] == 0 ? Double.NaN : sums[idx] / counts[idx];
        }

        public double edge(int idx) {
            return mean(idx) - baselineMean;
        }

        public double hitRate(int idx) {
            return counts[idx] == 0 ? Double.NaN : 100.0 * positives[idx] / counts[idx];
        }
    }

    /** One window's results, partitioned by regime. */
    public record WindowStats(int[] combos, RegimeStats bull, RegimeStats bear) {}

    /** Every combination of 1..maxSize primitives, as bit masks. */
    public int[] combinations(int maxSize) {
        List<Integer> masks = new ArrayList<>();
        for (int mask = 1; mask < (1 << PRIMITIVES.length); mask++) {
            if (Integer.bitCount(mask) <= maxSize) {
                masks.add(mask);
            }
        }
        return masks.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Resolve primitive names to a bit mask.
     *
     * @throws IllegalArgumentException if a name is not a known primitive — a silently
     *                                  ignored typo would turn a pre-registered test into
     *                                  a test of something else
     */
    public int maskOf(List<String> primitiveNames) {
        int mask = 0;
        for (String name : primitiveNames) {
            int bit = -1;
            for (int b = 0; b < PRIMITIVES.length; b++) {
                if (PRIMITIVES[b].equalsIgnoreCase(name.trim())) {
                    bit = b;
                    break;
                }
            }
            if (bit < 0) {
                throw new IllegalArgumentException("Unknown primitive '" + name
                        + "'. Known: " + String.join(", ", PRIMITIVES));
            }
            mask |= 1 << bit;
        }
        return mask;
    }

    /** Human-readable name for a mask. */
    public String describe(int mask) {
        StringBuilder sb = new StringBuilder();
        for (int b = 0; b < PRIMITIVES.length; b++) {
            if ((mask & (1 << b)) != 0) {
                if (!sb.isEmpty()) {
                    sb.append(" + ");
                }
                sb.append(PRIMITIVES[b]);
            }
        }
        return sb.toString();
    }

    /** Score every combination over one window, pooled across symbols, split by regime. */
    public WindowStats profile(List<ChartData> charts, int[] combos) {
        Accumulator bull = new Accumulator(combos.length);
        Accumulator bear = new Accumulator(combos.length);

        for (ChartData chart : charts) {
            BarData data;
            try {
                data = barData(chart);
            } catch (RuntimeException ex) {
                continue;   // too little history for this symbol in this window
            }

            for (int i = 0; i < data.masks().length; i++) {
                if (data.masks()[i] == -1) {
                    continue;
                }
                Accumulator target = data.bull()[i] ? bull : bear;
                target.bars++;
                target.baselineSum += data.forward()[i];

                int barMask = data.masks()[i];
                for (int c = 0; c < combos.length; c++) {
                    // Every member of the combination must be active on this bar.
                    if ((barMask & combos[c]) == combos[c]) {
                        target.counts[c]++;
                        target.sums[c] += data.forward()[i];
                        if (data.forward()[i] > 0) {
                            target.positives[c]++;
                        }
                    }
                }
            }
        }

        return new WindowStats(combos, bull.toStats(), bear.toStats());
    }

    private static final class Accumulator {
        final long[] counts;
        final double[] sums;
        final long[] positives;
        double baselineSum;
        int bars;

        Accumulator(int size) {
            counts = new long[size];
            sums = new double[size];
            positives = new long[size];
        }

        RegimeStats toStats() {
            return new RegimeStats(counts, sums, positives,
                    bars == 0 ? 0 : baselineSum / bars, bars);
        }
    }

    /** Per-bar masks, regime flags and forward returns. */
    private record BarData(int[] masks, boolean[] bull, double[] forward) {}

    private BarData barData(ChartData chart) {
        CipherB.Series s = CipherB.computeSeries(chart.candles());
        int n = s.length();

        BarSeries series = BarSeriesAdapter.toBarSeries(chart);
        SuperTrendIndicator superTrend = new SuperTrendIndicator(series, 10, 3.0);
        MoneyFlowIndexIndicator mfi = new MoneyFlowIndexIndicator(series, 14);

        double[] ema50 = Ta.ema(s.close(), 50);
        double[] ema200 = Ta.ema(s.close(), 200);
        double[] atr = s.atr();
        double[] volume = new double[n];
        for (int i = 0; i < n; i++) {
            volume[i] = chart.candles().get(i).volume();
        }
        double[] volAvg = Ta.sma(volume, 50);

        List<Candle> candles = chart.candles();
        int[] masks = new int[n];
        boolean[] bull = new boolean[n];
        double[] forward = new double[n];

        for (int i = 0; i < n; i++) {
            if (i < 250 || i >= n - HORIZON
                    || Double.isNaN(atr[i]) || atr[i] <= 0
                    || Double.isNaN(ema50[i]) || Double.isNaN(ema200[i])
                    || Double.isNaN(ema200[i - 200])) {
                masks[i] = -1;
                continue;
            }

            double close = candles.get(i).close();
            forward[i] = close == 0 ? 0 : (candles.get(i + HORIZON).close() - close) / close * 100.0;
            // Regime: the long-horizon trend, used to partition rather than to filter.
            bull[i] = ema200[i] > ema200[i - 200];

            double rsi = s.rsi()[i];
            double wt2 = s.wt2()[i];
            double stretch = (close - ema50[i]) / atr[i];
            double mfiValue = mfi.getValue(i).doubleValue();
            double volRatio = Double.isNaN(volAvg[i]) || volAvg[i] <= 0 ? 1 : volume[i] / volAvg[i];

            int m = 0;
            if (rsi < 30) m |= 1;
            if (rsi > 70) m |= 1 << 1;
            if (wt2 <= -53) m |= 1 << 2;
            if (wt2 >= 60) m |= 1 << 3;
            if (s.buyDot()[i]) m |= 1 << 4;
            if (s.sellDot()[i]) m |= 1 << 5;
            if (s.bullDiv()[i]) m |= 1 << 6;
            if (s.bearDiv()[i]) m |= 1 << 7;
            if (mfiValue < 20) m |= 1 << 8;
            if (mfiValue > 80) m |= 1 << 9;
            if (stretch <= -2.0) m |= 1 << 10;
            if (stretch >= 2.0) m |= 1 << 11;
            if (volRatio >= 1.5) m |= 1 << 12;
            if (superTrend.isUpTrend(i)) m |= 1 << 13;
            if (superTrend.trendChanged(i) && superTrend.isUpTrend(i)) m |= 1 << 14;
            if (wt2 >= 53) m |= 1 << 15;
            masks[i] = m;
        }
        return new BarData(masks, bull, forward);
    }
}
