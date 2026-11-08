package com.tradingstrat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Live execution settings ({@code tradingstrat.live.*}).
 *
 * <p>Two independent safety switches, both defaulting to the safe position:
 * {@code enabled} gates the scheduler entirely, and {@code dryRun} gates order placement.
 * Real orders require deliberately turning both.
 *
 * <p>Market data and order routing are configured separately on purpose. Klines always
 * come from {@link ChartApiProperties#baseUrl()} (production), because the testnet's own
 * candles are synthetic — pointing the strategy at them would make a dry run meaningless.
 * Only {@link #tradeBaseUrl()} switches between testnet and production for orders.
 *
 * @param quotePerSymbol quote currency committed per symbol on entry, e.g. 100 USDT
 * @param stateDir       where the position file lives; hysteresis is stateful, so a
 *                       restart that forgets the current position will misfire
 */
@ConfigurationProperties(prefix = "tradingstrat.live")
public record LiveExecutorProperties(
        boolean enabled,
        boolean dryRun,
        List<String> symbols,
        String interval,
        int emaLen,
        double bandPct,
        double quotePerSymbol,
        String quoteAsset,
        String tradeBaseUrl,
        String apiKey,
        String apiSecret,
        String stateDir,
        int klineBars
) {

    /** True when orders would actually reach an exchange. */
    public boolean willPlaceRealOrders() {
        return enabled && !dryRun;
    }

    /** Testnet hosts carry "testnet" in the hostname; used only for logging clarity. */
    public boolean isTestnet() {
        return tradeBaseUrl != null && tradeBaseUrl.contains("testnet");
    }
}
