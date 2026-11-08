package com.tradingstrat.indicator;

/**
 * Pine Script-equivalent technical analysis primitives.
 *
 * Semantics deliberately mirror TradingView's {@code ta.*} functions so that
 * indicator output matches the original scripts:
 * <ul>
 *   <li>Series are index-aligned with the input; warm-up bars are {@link Double#NaN}
 *       (Pine's {@code na}).</li>
 *   <li>{@code ema} and {@code rma} seed from an SMA at the first fully-formed bar,
 *       exactly as Pine does — not from the first sample.</li>
 * </ul>
 */
public final class Ta {

    private Ta() {}

    /**
     * Pine {@code ta.sma}: na until {@code len} bars are available.
     * Leading na bars in the source are skipped, as Pine does — the average starts
     * at the first bar with a full window of valid data.
     */
    public static double[] sma(double[] src, int len) {
        int n = src.length;
        double[] out = nanArray(n);
        int start = firstValid(src);
        if (start < 0 || n - start < len) {
            return out;
        }
        double sum = 0.0;
        for (int i = start; i < n; i++) {
            sum += src[i];
            if (i >= start + len) {
                sum -= src[i - len];
            }
            if (i >= start + len - 1) {
                out[i] = sum / len;
            }
        }
        return out;
    }

    /** Pine {@code ta.ema}: alpha = 2/(len+1), seeded with SMA at bar {@code len-1}. */
    public static double[] ema(double[] src, int len) {
        return smoothed(src, len, 2.0 / (len + 1));
    }

    /** Pine {@code ta.rma} (Wilder): alpha = 1/len, seeded with SMA at bar {@code len-1}. */
    public static double[] rma(double[] src, int len) {
        return smoothed(src, len, 1.0 / len);
    }

    private static double[] smoothed(double[] src, int len, double alpha) {
        int n = src.length;
        double[] out = nanArray(n);
        int start = firstValid(src);
        if (start < 0 || n - start < len) {
            return out;
        }
        double seed = 0.0;
        for (int i = start; i < start + len; i++) {
            seed += src[i];
        }
        int seedIdx = start + len - 1;
        out[seedIdx] = seed / len;
        for (int i = seedIdx + 1; i < n; i++) {
            out[i] = alpha * src[i] + (1 - alpha) * out[i - 1];
        }
        return out;
    }

    /** Index of the first non-NaN value, or -1 if the series is entirely NaN. */
    private static int firstValid(double[] src) {
        for (int i = 0; i < src.length; i++) {
            if (!Double.isNaN(src[i])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Pine {@code ta.rsi}:
     * {@code up = rma(max(change,0), len)}, {@code down = rma(-min(change,0), len)}.
     */
    public static double[] rsi(double[] src, int len) {
        int n = src.length;
        double[] up = new double[n];
        double[] down = new double[n];
        for (int i = 1; i < n; i++) {
            double change = src[i] - src[i - 1];
            up[i] = Math.max(change, 0);
            down[i] = -Math.min(change, 0);
        }
        // Pine's change() is na on bar 0; rma over it effectively starts at bar 1.
        double[] rmaUp = rma(up, len);
        double[] rmaDown = rma(down, len);

        double[] out = nanArray(n);
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(rmaUp[i]) || Double.isNaN(rmaDown[i])) {
                continue;
            }
            if (rmaDown[i] == 0) {
                out[i] = 100;
            } else if (rmaUp[i] == 0) {
                out[i] = 0;
            } else {
                out[i] = 100 - (100 / (1 + rmaUp[i] / rmaDown[i]));
            }
        }
        return out;
    }

    /**
     * Pine {@code ta.stoch(src, high, low, len)}:
     * {@code 100 * (src - lowest(low, len)) / (highest(high, len) - lowest(low, len))}.
     */
    public static double[] stoch(double[] src, double[] high, double[] low, int len) {
        int n = src.length;
        double[] hh = highest(high, len);
        double[] ll = lowest(low, len);
        double[] out = nanArray(n);
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(hh[i]) || Double.isNaN(ll[i]) || Double.isNaN(src[i])) {
                continue;
            }
            double range = hh[i] - ll[i];
            out[i] = range == 0 ? 0 : 100 * (src[i] - ll[i]) / range;
        }
        return out;
    }

    /** Pine {@code ta.highest}: requires a full window of valid data. */
    public static double[] highest(double[] src, int len) {
        return extreme(src, len, true);
    }

    /** Pine {@code ta.lowest}: requires a full window of valid data. */
    public static double[] lowest(double[] src, int len) {
        return extreme(src, len, false);
    }

    private static double[] extreme(double[] src, int len, boolean max) {
        int n = src.length;
        double[] out = nanArray(n);
        int start = firstValid(src);
        if (start < 0) {
            return out;
        }
        for (int i = start + len - 1; i < n; i++) {
            double best = max ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            boolean valid = true;
            for (int j = i - len + 1; j <= i; j++) {
                if (Double.isNaN(src[j])) {
                    valid = false;
                    break;
                }
                best = max ? Math.max(best, src[j]) : Math.min(best, src[j]);
            }
            if (valid) {
                out[i] = best;
            }
        }
        return out;
    }

    /**
     * Pine {@code ta.atr(len)}: Wilder-smoothed true range.
     *
     * True range is the greatest of the current bar's span, and the gap from the previous
     * close to this bar's high or low — so it accounts for overnight/-session gaps that a
     * plain high-low would miss.
     */
    public static double[] atr(double[] high, double[] low, double[] close, int len) {
        int n = close.length;
        double[] tr = new double[n];
        tr[0] = high[0] - low[0];
        for (int i = 1; i < n; i++) {
            tr[i] = Math.max(high[i] - low[i],
                    Math.max(Math.abs(high[i] - close[i - 1]), Math.abs(low[i] - close[i - 1])));
        }
        return rma(tr, len);
    }

    /**
     * Pine {@code ta.pivothigh(src, left, right)}: a pivot is confirmed {@code right} bars
     * late, so output bar {@code i} carries the pivot value of bar {@code i - right}
     * when that bar tops the {@code left} bars before and the {@code right} bars after it.
     * Non-pivot bars are NaN.
     *
     * <p><b>Ties deviate from Pine deliberately.</b> Pine requires a strict maximum, which
     * throws away flat and double tops — precisely the levels that matter most for
     * support/resistance. Here equal values are tolerated on the left and rejected on the
     * right, so the <em>last</em> bar of a plateau is the pivot and a flat top registers
     * exactly once. Nothing needing Pine parity uses this: {@link Divergence} carries its
     * own strict {@code f_fractalize} implementation.
     */
    public static double[] pivotHigh(double[] src, int left, int right) {
        return pivot(src, left, right, true);
    }

    /** Pine {@code ta.pivotlow}: mirror of {@link #pivotHigh}. */
    public static double[] pivotLow(double[] src, int left, int right) {
        return pivot(src, left, right, false);
    }

    private static double[] pivot(double[] src, int left, int right, boolean high) {
        int n = src.length;
        double[] out = nanArray(n);
        for (int i = left + right; i < n; i++) {
            int p = i - right;
            double candidate = src[p];
            if (Double.isNaN(candidate)) {
                continue;
            }
            boolean isPivot = true;
            for (int k = p - left; k <= p + right && isPivot; k++) {
                if (k == p) {
                    continue;
                }
                // Equal values are allowed to the left and rejected to the right, so a
                // plateau yields its final bar as the single pivot.
                boolean onLeft = k < p;
                boolean blocks = high
                        ? (onLeft ? src[k] > candidate : src[k] >= candidate)
                        : (onLeft ? src[k] < candidate : src[k] <= candidate);
                if (Double.isNaN(src[k]) || blocks) {
                    isPivot = false;
                }
            }
            if (isPivot) {
                out[i] = candidate;
            }
        }
        return out;
    }

    /** Pine {@code ta.cross}: true on bars where a and b crossed in either direction. */
    public static boolean[] cross(double[] a, double[] b) {
        int n = a.length;
        boolean[] out = new boolean[n];
        for (int i = 1; i < n; i++) {
            if (Double.isNaN(a[i]) || Double.isNaN(b[i])
                    || Double.isNaN(a[i - 1]) || Double.isNaN(b[i - 1])) {
                continue;
            }
            double prev = a[i - 1] - b[i - 1];
            double curr = a[i] - b[i];
            out[i] = (prev <= 0 && curr > 0) || (prev >= 0 && curr < 0);
        }
        return out;
    }

    /**
     * Pine {@code valuewhen(cond, src, 0)}: for each bar, the value of {@code src}
     * at the most recent bar where {@code cond} held (including the current bar).
     */
    public static double[] valueWhen(boolean[] cond, double[] src) {
        int n = cond.length;
        double[] out = nanArray(n);
        double last = Double.NaN;
        for (int i = 0; i < n; i++) {
            if (cond[i]) {
                last = src[i];
            }
            out[i] = last;
        }
        return out;
    }

    /** Pine's history operator {@code series[n]}. */
    public static double[] shift(double[] src, int n) {
        int len = src.length;
        double[] out = nanArray(len);
        for (int i = n; i < len; i++) {
            out[i] = src[i - n];
        }
        return out;
    }

    /** Element-wise average, used by the Stoch RSI "average K/D" option. */
    public static double[] avg(double[] a, double[] b) {
        int n = a.length;
        double[] out = nanArray(n);
        for (int i = 0; i < n; i++) {
            out[i] = (a[i] + b[i]) / 2.0;
        }
        return out;
    }

    static double[] nanArray(int n) {
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        return out;
    }
}
