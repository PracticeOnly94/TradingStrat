package com.tradingstrat.config;

/**
 * How stops and targets are placed.
 *
 * <p>Measured on 233 trades, {@link #LEVELS} produced an average win of 0.97% over a
 * 1.9-bar hold. Against a ~0.2% round-trip cost that leaves fees eating a fifth of every
 * winner, which is why the exit is the highest-leverage part of this strategy: the edge
 * is not too small so much as the capture is.
 *
 * <p>The cause is structural — after a flush, the nearest resistance overhead is close
 * while the nearest support below is far, so level-based exits hand a dip-buyer a
 * short target and a wide stop.
 */
public enum ExitMode {

    /** Nearest support/resistance either side of entry. The original behaviour. */
    LEVELS,

    /** Stop and target set as multiples of ATR, so both scale with volatility. */
    ATR,

    /**
     * ATR stop, but the position is held until the opposite Cipher B dot prints —
     * the indicator's own exit, which lets a winner run to the next extreme instead of
     * stopping at the first level overhead.
     */
    OPPOSITE_DOT
}
