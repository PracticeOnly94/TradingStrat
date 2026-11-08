package com.tradingstrat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The range-position + RSI setup ({@code tradingstrat.range-rsi.*}).
 *
 * <p>Measured positive in 8 of 8 windows across two independent symbol sets — the most
 * consistent result of the project. Kept to two conditions because the combination
 * search showed that stacking further destroys the signal.
 *
 * @param rangeLookback   bars defining the price envelope
 * @param rangePctileMax  enter only in the bottom this % of that envelope
 * @param rsiMax          RSI must be below this
 * @param entryOnRecovery enter when the setup <em>resolves</em> (RSI crosses back above
 *                        {@code rsiMax}) rather than on its first bar. The conditional
 *                        study counted every qualifying bar, but a backtest can hold only
 *                        one position per symbol, so entering immediately takes the first
 *                        and worst bar of each oversold cluster and misses the rest —
 *                        the selection effect that broke the previous backtest
 * @param atrStopMult     protective stop in ATRs; 0 parks it far away
 */
@ConfigurationProperties(prefix = "tradingstrat.range-rsi")
public record RangeRsiProperties(
        int rangeLookback,
        double rangePctileMax,
        double rsiMax,
        boolean entryOnRecovery,
        double atrStopMult
) {}
