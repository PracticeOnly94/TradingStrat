package com.tradingstrat.indicator;

import com.tradingstrat.model.Candle;

import java.util.List;

/**
 * VuManChu Cipher B + Divergences — custom indicator.
 *
 * This is an <em>indicator</em>, not a strategy: it reports what the chart is doing
 * (WaveTrend, money flow, RSI/Stoch RSI, divergences and the resulting dots).
 * It makes no trade decision and produces no stop-loss or target — that belongs to
 * a strategy layer built on top of these signals.
 *
 * Defaults match the published Pine inputs. Implemented: WaveTrend + OB/OS dots,
 * RSI+MFI money-flow ribbon, RSI, Stochastic RSI, WaveTrend/RSI/Stoch divergences
 * (regular and hidden), and the "gold buy" signal.
 * Not implemented: Sommi flags/diamonds (multi-timeframe overlays) and the
 * purely cosmetic plot styling.
 *
 * Two views are available:
 * <ul>
 *   <li>{@link #evaluate} — full state on the latest candle, including divergences.</li>
 *   <li>{@link #computeSeries} — index-aligned series, for strategies that need
 *       history (e.g. comparing successive dots, or whether a value is rising).</li>
 * </ul>
 */
public final class CipherB {

    private CipherB() {}

    /** Published Pine input defaults. */
    public record Params(
            int wtChannelLen, int wtAverageLen, int wtMALen,
            double obLevel, double obLevel2, double obLevel3,
            double osLevel, double osLevel2, double osLevel3,
            double wtDivObLevel, double wtDivOsLevel,
            double wtDivObLevelAdd, double wtDivOsLevelAdd,
            int rsiMfiPeriod, double rsiMfiMultiplier, double rsiMfiPosY,
            int rsiLen, double rsiOversold, double rsiOverbought,
            int stochLen, int stochRsiLen, int stochKSmooth, int stochDSmooth,
            boolean stochUseLog, boolean stochAvg
    ) {
        public static Params defaults() {
            return new Params(
                    9, 12, 3,
                    53, 60, 100,
                    -53, -60, -75,
                    45, -65,
                    15, -40,
                    60, 150, 2.5,
                    14, 30, 60,
                    14, 14, 3, 3,
                    true, false
            );
        }
    }

    /**
     * Indicator state on the most recent candle.
     *
     * @param buyDot         green dot: WaveTrend cross up while oversold
     * @param sellDot        red dot: WaveTrend cross down while overbought
     * @param goldBuy        "gold" buy: bullish divergence out of an extreme oversold low
     * @param moneyFlow      RSI+MFI ribbon value; &gt; 0 = net buying pressure
     */
    public record Signals(
            double wt1,
            double wt2,
            double wtVwap,
            boolean wtCross,
            boolean overbought,
            boolean oversold,
            boolean buyDot,
            boolean sellDot,
            boolean goldBuy,
            double moneyFlow,
            boolean moneyFlowBullish,
            double rsi,
            double stochK,
            double stochD,
            boolean wtBullDiv,
            boolean wtBearDiv,
            boolean wtBullDivHidden,
            boolean wtBearDivHidden,
            boolean rsiBullDiv,
            boolean rsiBearDiv,
            boolean stochBullDiv,
            boolean stochBearDiv
    ) {
        /** Any bullish divergence across WaveTrend (incl. the wider range), RSI or Stoch. */
        public boolean anyBullDiv() {
            return wtBullDiv || rsiBullDiv || stochBullDiv;
        }

        /** Any bearish divergence across WaveTrend (incl. the wider range), RSI or Stoch. */
        public boolean anyBearDiv() {
            return wtBearDiv || rsiBearDiv || stochBearDiv;
        }
    }

    /**
     * Index-aligned indicator series. Warm-up bars are {@link Double#NaN}.
     * Exposes the raw OHLC alongside the oscillators so strategies can derive
     * trend/swing levels without re-unpacking the candle list.
     */
    public record Series(
            double[] wt1,
            double[] wt2,
            double[] wtVwap,
            boolean[] overbought,
            boolean[] oversold,
            boolean[] buyDot,
            boolean[] sellDot,
            boolean[] goldBuy,
            boolean[] bullDiv,
            boolean[] bearDiv,
            double[] moneyFlow,
            double[] rsi,
            double[] stochK,
            double[] stochD,
            double[] atr,
            double[] open,
            double[] high,
            double[] low,
            double[] close
    ) {
        public int length() {
            return close.length;
        }

        /** Index of the last bar. */
        public int last() {
            return close.length - 1;
        }
    }

    /** Evaluate with the published defaults. */
    public static Signals evaluate(List<Candle> candles) {
        return evaluate(candles, Params.defaults());
    }

    /** Series with the published defaults. */
    public static Series computeSeries(List<Candle> candles) {
        return computeSeries(candles, Params.defaults());
    }

    /**
     * Compute the full indicator series.
     *
     * @param candles ordered oldest → newest
     * @throws IllegalArgumentException if there is not enough history to warm up the oscillators
     */
    public static Series computeSeries(List<Candle> candles, Params p) {
        Base b = base(candles, p);
        int n = b.close.length;

        boolean[] buyDot = new boolean[n];
        boolean[] sellDot = new boolean[n];
        for (int i = 0; i < n; i++) {
            buyDot[i] = b.wt.cross()[i] && b.wt.crossUp()[i] && b.wt.oversold()[i];
            sellDot[i] = b.wt.cross()[i] && b.wt.crossDown()[i] && b.wt.overbought()[i];
        }

        // Divergences and the gold buy, over the whole series rather than only the last
        // bar. Every value at index i depends solely on bars <= i, so reading them during
        // a bar-by-bar replay introduces no lookahead.
        Divergence.Result wtDiv = Divergence.find(
                b.wt.wt2(), b.high, b.low, p.wtDivObLevel(), p.wtDivOsLevel(), true);
        Divergence.Result wtDivAdd = Divergence.find(
                b.wt.wt2(), b.high, b.low, p.wtDivObLevelAdd(), p.wtDivOsLevelAdd(), true);
        Divergence.Result rsiDiv = Divergence.find(b.rsi, b.high, b.low, 0, 0, false);
        Divergence.Result stochDiv = Divergence.find(b.stochK, b.high, b.low, 0, 0, false);

        double[] lastRsi = Ta.shift(Ta.valueWhen(wtDiv.fractalBot(), Ta.shift(b.rsi, 2)), 2);

        boolean[] bullDiv = new boolean[n];
        boolean[] bearDiv = new boolean[n];
        boolean[] goldBuy = new boolean[n];
        for (int i = 0; i < n; i++) {
            boolean wtBull = wtDiv.bullDiv()[i] || wtDivAdd.bullDiv()[i];
            bullDiv[i] = wtBull || rsiDiv.bullDiv()[i] || stochDiv.bullDiv()[i];
            bearDiv[i] = wtDiv.bearDiv()[i] || wtDivAdd.bearDiv()[i]
                    || rsiDiv.bearDiv()[i] || stochDiv.bearDiv()[i];

            double wtLowPrev = wtDiv.lowPrev()[i];
            goldBuy[i] = (wtBull || rsiDiv.bullDiv()[i])
                    && !Double.isNaN(wtLowPrev)
                    && !Double.isNaN(lastRsi[i])
                    && wtLowPrev <= p.osLevel3()
                    && b.wt.wt2()[i] > p.osLevel3()
                    && wtLowPrev - b.wt.wt2()[i] <= -5
                    && lastRsi[i] < 30;
        }

        double[] atr = Ta.atr(b.high, b.low, b.close, 14);

        return new Series(
                b.wt.wt1(), b.wt.wt2(), b.wt.wtVwap(),
                b.wt.overbought(), b.wt.oversold(),
                buyDot, sellDot, goldBuy, bullDiv, bearDiv,
                b.moneyFlow, b.rsi, b.stochK, b.stochD, atr,
                b.open, b.high, b.low, b.close
        );
    }

    /**
     * Evaluate the indicator on the latest candle.
     *
     * @param candles ordered oldest → newest
     * @throws IllegalArgumentException if there is not enough history to warm up the oscillators
     */
    public static Signals evaluate(List<Candle> candles, Params p) {
        Base b = base(candles, p);
        int n = b.close.length;
        WaveTrend.Result wt = b.wt;

        // Divergences: WaveTrend at the normal range, at the wider "add" range, RSI and Stoch.
        Divergence.Result wtDiv = Divergence.find(
                wt.wt2(), b.high, b.low, p.wtDivObLevel(), p.wtDivOsLevel(), true);
        Divergence.Result wtDivAdd = Divergence.find(
                wt.wt2(), b.high, b.low, p.wtDivObLevelAdd(), p.wtDivOsLevelAdd(), true);
        Divergence.Result rsiDiv = Divergence.find(b.rsi, b.high, b.low, 0, 0, false);
        Divergence.Result stochDiv = Divergence.find(b.stochK, b.high, b.low, 0, 0, false);

        int i = n - 1;

        boolean wtBullDiv = wtDiv.bullDiv()[i] || wtDivAdd.bullDiv()[i];
        boolean wtBearDiv = wtDiv.bearDiv()[i] || wtDivAdd.bearDiv()[i];

        // Gold buy: bullish divergence lifting out of an extreme oversold low with RSI < 30.
        double[] lastRsiSeries = Ta.shift(Ta.valueWhen(wtDiv.fractalBot(), Ta.shift(b.rsi, 2)), 2);
        double wtLowPrev = wtDiv.lowPrev()[i];
        double lastRsi = lastRsiSeries[i];
        boolean goldBuy = (wtBullDiv || rsiDiv.bullDiv()[i])
                && !Double.isNaN(wtLowPrev)
                && !Double.isNaN(lastRsi)
                && wtLowPrev <= p.osLevel3()
                && wt.wt2()[i] > p.osLevel3()
                && wtLowPrev - wt.wt2()[i] <= -5
                && lastRsi < 30;

        return new Signals(
                wt.wt1()[i],
                wt.wt2()[i],
                wt.wtVwap()[i],
                wt.cross()[i],
                wt.overbought()[i],
                wt.oversold()[i],
                wt.cross()[i] && wt.crossUp()[i] && wt.oversold()[i],    // buySignal
                wt.cross()[i] && wt.crossDown()[i] && wt.overbought()[i], // sellSignal
                goldBuy,
                b.moneyFlow[i],
                b.moneyFlow[i] > 0,
                b.rsi[i],
                b.stochK[i],
                b.stochD[i],
                wtBullDiv,
                wtBearDiv,
                wtDiv.bullDivHidden()[i] || wtDivAdd.bullDivHidden()[i],
                wtDiv.bearDivHidden()[i] || wtDivAdd.bearDivHidden()[i],
                rsiDiv.bullDiv()[i],
                rsiDiv.bearDiv()[i],
                stochDiv.bullDiv()[i],
                stochDiv.bearDiv()[i]
        );
    }

    /** Shared oscillator computation behind both {@link #evaluate} and {@link #computeSeries}. */
    private record Base(
            double[] open, double[] high, double[] low, double[] close,
            WaveTrend.Result wt, double[] moneyFlow,
            double[] rsi, double[] stochK, double[] stochD
    ) {}

    private static Base base(List<Candle> candles, Params p) {
        int n = candles.size();
        int minBars = Math.max(p.rsiMfiPeriod(),
                p.wtChannelLen() + p.wtAverageLen() + p.wtMALen()) + 5;
        if (n < minBars) {
            throw new IllegalArgumentException(
                    "Not enough candles for Cipher B: have " + n + ", need at least " + minBars);
        }

        double[] open = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        double[] close = new double[n];
        double[] hlc3 = new double[n];
        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);
            open[i] = c.open();
            high[i] = c.high();
            low[i] = c.low();
            close[i] = c.close();
            hlc3[i] = (c.high() + c.low() + c.close()) / 3.0;
        }

        WaveTrend.Result wt = WaveTrend.compute(
                hlc3, p.wtChannelLen(), p.wtAverageLen(), p.wtMALen(), p.obLevel(), p.osLevel());

        double[] moneyFlow = rsiMfi(open, high, low, close,
                p.rsiMfiPeriod(), p.rsiMfiMultiplier(), p.rsiMfiPosY());

        double[] rsi = Ta.rsi(close, p.rsiLen());
        double[][] stoch = stochRsi(close, p);

        return new Base(open, high, low, close, wt, moneyFlow, rsi, stoch[0], stoch[1]);
    }

    /**
     * {@code f_rsimfi}: {@code sma(((close - open) / (high - low)) * multiplier, period) - posY}.
     */
    private static double[] rsiMfi(double[] open, double[] high, double[] low, double[] close,
                                   int period, double multiplier, double posY) {
        int n = close.length;
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            double range = high[i] - low[i];
            raw[i] = range == 0 ? 0 : ((close[i] - open[i]) / range) * multiplier;
        }
        double[] smoothed = Ta.sma(raw, period);
        double[] out = Ta.nanArray(n);
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(smoothed[i])) {
                out[i] = smoothed[i] - posY;
            }
        }
        return out;
    }

    /**
     * {@code f_stochrsi}: Stoch of RSI, optionally on log price, with K/D smoothing.
     *
     * @return {@code [k, d]}
     */
    private static double[][] stochRsi(double[] close, Params p) {
        int n = close.length;
        double[] src = new double[n];
        for (int i = 0; i < n; i++) {
            src[i] = p.stochUseLog() ? Math.log(close[i]) : close[i];
        }
        double[] rsi = Ta.rsi(src, p.stochRsiLen());
        double[] k = Ta.sma(Ta.stoch(rsi, rsi, rsi, p.stochLen()), p.stochKSmooth());
        double[] d = Ta.sma(k, p.stochDSmooth());
        return new double[][]{p.stochAvg() ? Ta.avg(k, d) : k, d};
    }
}
