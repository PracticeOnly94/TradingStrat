package com.tradingstrat.indicator;

/**
 * Fractal-based divergence detection, mirroring VuManChu's {@code f_fractalize}
 * and {@code f_findDivs}.
 *
 * A fractal is confirmed two bars late (it inspects {@code src[0..4]} and pivots on
 * {@code src[2]}), so all comparisons are made against bar {@code i-2} — the same
 * two-bar lag the original carries.
 */
public final class Divergence {

    private Divergence() {}

    /** Per-bar divergence flags, index-aligned with the input series. */
    public record Result(
            boolean[] fractalTop,
            boolean[] fractalBot,
            double[] lowPrev,
            boolean[] bearDiv,
            boolean[] bullDiv,
            boolean[] bearDivHidden,
            boolean[] bullDivHidden
    ) {}

    /** {@code f_top_fractal}: src[4] < src[2] and src[3] < src[2] and src[2] > src[1] and src[2] > src[0]. */
    private static boolean topFractal(double[] src, int i) {
        if (i < 4) {
            return false;
        }
        return src[i - 4] < src[i - 2]
                && src[i - 3] < src[i - 2]
                && src[i - 2] > src[i - 1]
                && src[i - 2] > src[i];
    }

    /** {@code f_bot_fractal}: inverse of {@link #topFractal}. */
    private static boolean botFractal(double[] src, int i) {
        if (i < 4) {
            return false;
        }
        return src[i - 4] > src[i - 2]
                && src[i - 3] > src[i - 2]
                && src[i - 2] < src[i - 1]
                && src[i - 2] < src[i];
    }

    /**
     * {@code f_findDivs(src, topLimit, botLimit, useLimits)}.
     *
     * @param src       oscillator series to test for divergence (wt2, rsi, stochK, ...)
     * @param high      candle highs, index-aligned with {@code src}
     * @param low       candle lows, index-aligned with {@code src}
     * @param topLimit  only count bearish fractals at or above this oscillator level
     * @param botLimit  only count bullish fractals at or below this oscillator level
     * @param useLimits whether the limits apply at all
     */
    public static Result find(double[] src, double[] high, double[] low,
                              double topLimit, double botLimit, boolean useLimits) {
        int n = src.length;
        boolean[] fractalTop = new boolean[n];
        boolean[] fractalBot = new boolean[n];

        for (int i = 0; i < n; i++) {
            if (i < 4 || hasNaN(src, i - 4, i)) {
                continue;
            }
            fractalTop[i] = topFractal(src, i) && (!useLimits || src[i - 2] >= topLimit);
            fractalBot[i] = botFractal(src, i) && (!useLimits || src[i - 2] <= botLimit);
        }

        double[] src2 = Ta.shift(src, 2);
        double[] high2 = Ta.shift(high, 2);
        double[] low2 = Ta.shift(low, 2);

        // valuewhen(fractal, x[2], 0)[2]
        double[] highPrev = Ta.shift(Ta.valueWhen(fractalTop, src2), 2);
        double[] highPrice = Ta.shift(Ta.valueWhen(fractalTop, high2), 2);
        double[] lowPrev = Ta.shift(Ta.valueWhen(fractalBot, src2), 2);
        double[] lowPrice = Ta.shift(Ta.valueWhen(fractalBot, low2), 2);

        boolean[] bearDiv = new boolean[n];
        boolean[] bullDiv = new boolean[n];
        boolean[] bearDivHidden = new boolean[n];
        boolean[] bullDivHidden = new boolean[n];

        for (int i = 0; i < n; i++) {
            if (i < 2) {
                continue;
            }
            double h2 = high2[i];
            double l2 = low2[i];
            double s2 = src2[i];

            if (fractalTop[i] && valid(h2, highPrice[i], s2, highPrev[i])) {
                bearDiv[i] = h2 > highPrice[i] && s2 < highPrev[i];
                bearDivHidden[i] = h2 < highPrice[i] && s2 > highPrev[i];
            }
            if (fractalBot[i] && valid(l2, lowPrice[i], s2, lowPrev[i])) {
                bullDiv[i] = l2 < lowPrice[i] && s2 > lowPrev[i];
                bullDivHidden[i] = l2 > lowPrice[i] && s2 < lowPrev[i];
            }
        }

        return new Result(fractalTop, fractalBot, lowPrev,
                bearDiv, bullDiv, bearDivHidden, bullDivHidden);
    }

    private static boolean valid(double... values) {
        for (double v : values) {
            if (Double.isNaN(v)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasNaN(double[] src, int from, int to) {
        for (int i = Math.max(0, from); i <= to; i++) {
            if (Double.isNaN(src[i])) {
                return true;
            }
        }
        return false;
    }
}
