package com.tradingstrat.api;

import java.util.List;

/**
 * Supplies the set of symbols to test a strategy against.
 *
 * Backtesting one pair produces too few setups to distinguish edge from noise, so the
 * universe exists to widen the sample across many markets at once.
 */
public interface SymbolUniverse {

    /**
     * The most liquid quote-USDT pairs, most liquid first.
     *
     * Implementations exclude instruments whose price cannot trend — pegged
     * stablecoin pairs — and leveraged tokens, since neither says anything about
     * whether a strategy works.
     */
    List<String> topByLiquidity(int limit);
}
