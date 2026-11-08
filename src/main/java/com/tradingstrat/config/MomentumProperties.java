package com.tradingstrat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Momentum strategy tunables ({@code tradingstrat.momentum.*}).
 *
 * <p>This strategy exists because the conditional forward-return study said so, not
 * because anyone proposed it. Over 94,040 bars of 1h majors, overbought readings were
 * followed by <em>rising</em> prices (wt2 &ge; 60 → +0.500% over 48 bars against a
 * +0.086% baseline) while oversold readings were followed by falling ones, monotonically
 * worse the deeper they went. That is the signature of momentum persistence, and it is
 * the exact opposite of what a mean-reversion indicator like Cipher B assumes.
 *
 * @param wt2Entry        wt2 level whose upward crossing opens a long (mirrored for shorts)
 * @param rsiEntry        RSI level required alongside, or 0 to ignore RSI
 * @param exitEmaLen      momentum is considered spent when price closes back through this EMA
 * @param atrStopMult     protective stop distance in ATRs
 * @param atrTargetMult   target distance in ATRs; deliberately wide so the exit signal,
 *                        not the target, usually ends the trade
 * @param requireBullTrend only trade in the direction of the long-horizon trend
 * @param macroEmaLen     EMA used for that trend test
 * @param macroBars       lookback for its slope
 */
@ConfigurationProperties(prefix = "tradingstrat.momentum")
public record MomentumProperties(
        double wt2Entry,
        double rsiEntry,
        int exitEmaLen,
        double atrStopMult,
        double atrTargetMult,
        boolean requireBullTrend,
        int macroEmaLen,
        int macroBars,
        boolean tradeLongs,
        boolean tradeShorts
) {}
