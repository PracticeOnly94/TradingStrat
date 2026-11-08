package com.tradingstrat.indicator;

/**
 * WaveTrend oscillator — VuManChu's {@code f_wavetrend}.
 *
 * <pre>
 *   esa = ema(src, chlen)
 *   de  = ema(abs(src - esa), chlen)
 *   ci  = (src - esa) / (0.015 * de)
 *   wt1 = ema(ci, avg)
 *   wt2 = sma(wt1, malen)
 *   wtVwap = wt1 - wt2
 * </pre>
 *
 * Uses Pine-accurate EMA/SMA seeding via {@link Ta}, so warm-up bars are NaN.
 */
public final class WaveTrend {

    private WaveTrend() {}

    /** Full WaveTrend series, index-aligned with the input. */
    public record Result(
            double[] wt1,
            double[] wt2,
            double[] wtVwap,
            boolean[] cross,
            boolean[] crossUp,
            boolean[] crossDown,
            boolean[] overbought,
            boolean[] oversold
    ) {}

    /**
     * @param src      source series, typically hlc3
     * @param chlen    channel length (default 9)
     * @param avg      average length (default 12)
     * @param malen    signal SMA length (default 3)
     * @param obLevel  overbought threshold (default 53)
     * @param osLevel  oversold threshold (default -53)
     */
    public static Result compute(double[] src, int chlen, int avg, int malen,
                                 double obLevel, double osLevel) {
        int n = src.length;

        double[] esa = Ta.ema(src, chlen);

        double[] absDiff = Ta.nanArray(n);
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(esa[i])) {
                absDiff[i] = Math.abs(src[i] - esa[i]);
            }
        }
        double[] de = Ta.ema(absDiff, chlen);

        double[] ci = Ta.nanArray(n);
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(esa[i]) || Double.isNaN(de[i])) {
                continue;
            }
            double denom = 0.015 * de[i];
            ci[i] = denom == 0 ? 0 : (src[i] - esa[i]) / denom;
        }

        double[] wt1 = Ta.ema(ci, avg);
        double[] wt2 = Ta.sma(wt1, malen);

        double[] wtVwap = Ta.nanArray(n);
        boolean[] overbought = new boolean[n];
        boolean[] oversold = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(wt1[i]) || Double.isNaN(wt2[i])) {
                continue;
            }
            wtVwap[i] = wt1[i] - wt2[i];
            overbought[i] = wt2[i] >= obLevel;
            oversold[i] = wt2[i] <= osLevel;
        }

        boolean[] cross = Ta.cross(wt1, wt2);

        // Pine: wtCrossUp = wt2 - wt1 <= 0, wtCrossDown = wt2 - wt1 >= 0
        boolean[] crossUp = new boolean[n];
        boolean[] crossDown = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(wt1[i]) || Double.isNaN(wt2[i])) {
                continue;
            }
            crossUp[i] = wt2[i] - wt1[i] <= 0;
            crossDown[i] = wt2[i] - wt1[i] >= 0;
        }

        return new Result(wt1, wt2, wtVwap, cross, crossUp, crossDown, overbought, oversold);
    }
}
