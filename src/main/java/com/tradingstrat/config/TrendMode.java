package com.tradingstrat.config;

/**
 * How the price-trend condition participates in the strategy.
 *
 * Measurement on BTCUSDT 1h showed this condition passing at 0% of green dots against
 * a ~53% baseline — a Cipher B dot requires a flush deep enough to put price below its
 * own moving average, so "oversold dot" and "price trending up" are close to mutually
 * exclusive. Making the mode configurable lets that trade-off be measured rather than
 * argued about.
 */
public enum TrendMode {

    /** Mandatory: no signal unless the trend agrees. The original behaviour. */
    GATE,

    /** Contributes a confluence point but cannot block a signal. */
    SCORED,

    /** Not evaluated. Treats the strategy as the counter-trend setup it structurally is. */
    OFF
}
