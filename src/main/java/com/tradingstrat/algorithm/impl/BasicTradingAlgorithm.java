package com.tradingstrat.algorithm.impl;

import com.tradingstrat.algorithm.TradingAlgorithm;
import com.tradingstrat.model.AnalysisResult;
import com.tradingstrat.model.ChartBundle;
import com.tradingstrat.model.ChartData;
import com.tradingstrat.model.Signal;
import org.springframework.stereotype.Component;

/**
 * Placeholder strategy — returns NONE until a real strategy is defined.
 *
 * The bundle gives access to every requested timeframe, for example:
 *
 *   ChartData oneHour  = bundle.get("1h");
 *   ChartData fourHour = bundle.get("4h");
 *
 * A strategy consumes indicator output and decides entries/exits, e.g.:
 *
 *   CipherB.Signals s = CipherB.evaluate(oneHour.candles());
 *   if (s.buyDot() && s.moneyFlowBullish()) { ... }
 *
 * Indicators (what the chart says) live in com.tradingstrat.indicator;
 * strategies (what to do about it) live here.
 */
@Component
public class BasicTradingAlgorithm implements TradingAlgorithm {

    @Override
    public String key() {
        return "basic";
    }

    @Override
    public AnalysisResult analyze(ChartBundle bundle) {
        // TODO: implement real strategy logic using bundle.get("1h"), bundle.get("4h"), etc.
        return new AnalysisResult(
                bundle.symbol(),
                String.join("+", bundle.charts().keySet()),
                Signal.NONE,
                0,
                0,
                "No conditions met — algorithm not yet implemented"
        );
    }
}
