package com.tradingstrat.config;

/**
 * Which earlier dot the oscillator trendline is drawn to, from the most recent dot.
 */
public enum DotAnchor {

    /** The dot immediately before the latest one. Strictest reading of "consecutive dots". */
    PREVIOUS,

    /**
     * The most extreme earlier dot — lowest {@code wt2} for longs, highest for shorts.
     * Anchors the line to the significant swing rather than whatever printed last.
     */
    LOWEST,

    /**
     * Any earlier dot within the lookback: the line qualifies if at least one anchor
     * produces a valid slope. Closest to drawing the line by hand.
     */
    ANY
}
