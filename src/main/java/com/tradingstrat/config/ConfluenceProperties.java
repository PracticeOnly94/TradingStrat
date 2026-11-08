package com.tradingstrat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the Cipher B confluence strategy
 * ({@code tradingstrat.confluence.*}).
 *
 * @param minScore        points required to fire a signal (max 6, or 5 without a higher timeframe)
 * @param dotMaxAgeBars   how recently the triggering dot must have printed
 * @param dotLookbackBars how far back to search for the previous dot when checking structure
 * @param mfLookback      bars back used to decide whether money flow is rising/falling
 * @param rsiLongMin      lower bound of the RSI "recovery" band for longs
 * @param rsiLongMax      upper bound of the RSI "recovery" band for longs
 * @param rsiShortMin     lower bound of the RSI "rolling over" band for shorts
 * @param rsiShortMax     upper bound of the RSI "rolling over" band for shorts
 * @param trendMode       whether the price trend gates, scores, or is ignored
 * @param trendRequirePriceSide whether price must also be on the correct side of the EMA;
 *                        measured at 0% of green dots, so it is the half to drop first
 * @param trendWindow     whether the trend is measured across the dot window or a fixed
 *                        macro horizon
 * @param macroTrendEmaLen EMA length used in {@link TrendWindow#MACRO}
 * @param macroTrendBars  lookback for the macro EMA slope, in bars
 * @param htfTrendGlobalMode how the higher timeframe's macro trend participates
 * @param htfTrendLocalMode  how the higher timeframe's recent trend participates
 * @param htfLocalEmaLen  EMA length for the higher timeframe's local trend
 * @param htfLocalBars    lookback for the higher timeframe's local EMA slope
 * @param trendEmaLen     EMA length used in {@link TrendWindow#DOT_WINDOW}
 * @param trendSlopeBars  bars back used to measure EMA slope (1 = single-bar slope, noisy)
 * @param dotMinSlope     minimum per-bar slope of the line joining the last two dots;
 *                        0 means any upward (long) or downward (short) slope qualifies
 * @param dotAnchor       which earlier dot the trendline is drawn to
 * @param swingLookback   bars used to find the swing low/high for the stop
 * @param rewardRisk      target distance as a multiple of the stop distance
 * @param exitMode        how stops and targets are placed
 * @param atrLen          ATR length for {@link ExitMode#ATR} and {@link ExitMode#OPPOSITE_DOT}
 * @param atrStopMult     stop distance in ATRs
 * @param atrTargetMult   target distance in ATRs
 * @param rsiMode         whether the RSI band scores, gates, or is ignored
 * @param wtVwapMode      whether wtVwap scores, gates, or is ignored; it is near-implied
 *                        by the dot itself, so it is a candidate for OFF
 * @param requireGoldBuy  only take Cipher B "gold buy" signals
 * @param requireDivergence only take dots accompanied by a divergence
 * @param dotOversoldLevel wt2 must be at or below this for a dot to count; the Cipher B
 *                        default is -53, with -60 and -75 marking deeper capitulation
 * @param tradeLongs      whether long signals are taken
 * @param tradeShorts     whether short signals are taken
 */
@ConfigurationProperties(prefix = "tradingstrat.confluence")
public record ConfluenceProperties(
        int minScore,
        int dotMaxAgeBars,
        int dotLookbackBars,
        int mfLookback,
        double rsiLongMin,
        double rsiLongMax,
        double rsiShortMin,
        double rsiShortMax,
        TrendMode trendMode,
        TrendWindow trendWindow,
        boolean trendRequirePriceSide,
        int macroTrendEmaLen,
        int macroTrendBars,
        TrendMode htfTrendGlobalMode,
        TrendMode htfTrendLocalMode,
        int htfLocalEmaLen,
        int htfLocalBars,
        int trendEmaLen,
        int trendSlopeBars,
        double dotMinSlope,
        DotAnchor dotAnchor,
        int swingLookback,
        double rewardRisk,
        ExitMode exitMode,
        int atrLen,
        double atrStopMult,
        double atrTargetMult,
        TrendMode rsiMode,
        TrendMode wtVwapMode,
        boolean requireGoldBuy,
        boolean requireDivergence,
        double dotOversoldLevel,
        boolean tradeLongs,
        boolean tradeShorts
) {}
