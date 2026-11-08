package com.tradingstrat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two setups that replicated out-of-sample ({@code tradingstrat.measured-edge.*}).
 *
 * <p>Deliberately minimal. Both setups came from a combination search over 9,948
 * hypotheses under a search/holdout split, then a pre-registered test on 30 symbols no
 * search had touched. Adding any further condition would be a new, untested hypothesis —
 * and the same search showed that stacking beyond two conditions killed the signal in
 * every one of 8,373 larger combinations.
 *
 * @param longRsiMax     long leg: RSI below this
 * @param longWt2Max     long leg: wt2 at or below this
 * @param shortWt2Min    short leg: wt2 at or above this
 * @param requireBearDiv short leg: also require a bearish divergence
 * @param atrStopMult    protective stop in ATRs; 0 places it far enough never to trigger,
 *                       which reproduces the unstopped measurement
 * @param macroEmaLen    EMA defining the regime
 * @param macroBars      lookback for the regime EMA's slope
 */
@ConfigurationProperties(prefix = "tradingstrat.measured-edge")
public record MeasuredEdgeProperties(
        boolean tradeLongs,
        boolean tradeShorts,
        double longRsiMax,
        double longWt2Max,
        double shortWt2Min,
        boolean requireBearDiv,
        double atrStopMult,
        int macroEmaLen,
        int macroBars
) {}
