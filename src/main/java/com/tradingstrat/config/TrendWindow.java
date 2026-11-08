package com.tradingstrat.config;

/**
 * Over what horizon the price trend is measured.
 *
 * <p>Measured across BTC/ETH/SOL 1h (48 qualifying dots), the choice dominates the
 * outcome: {@link #DOT_WINDOW} passed 6.3% of dots while {@link #MACRO} passed 41.7%
 * against a ~50% baseline. 20 of the 48 dots sat inside a rising macro trend while the
 * dot window sloped down, and every one was rejected.
 *
 * <p>The cause is the window itself: anchor→latest spans run 2–44 bars (median 8–17),
 * and that span is defined by the flush being traded. Measuring a long EMA's slope over
 * it measures the flush rather than the trend.
 */
public enum TrendWindow {

    /**
     * Slope between the anchor dot and the latest dot. Keeps every gate on one window,
     * but makes the trend check myopic — retained for comparison, not recommended.
     */
    DOT_WINDOW,

    /**
     * Slope of a long EMA over a fixed lookback, evaluated at the signal bar and
     * independent of dot spacing. Expresses "is this flush happening inside an uptrend".
     */
    MACRO
}
