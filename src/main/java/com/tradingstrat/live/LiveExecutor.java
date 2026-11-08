package com.tradingstrat.live;

import com.tradingstrat.api.ChartDataClient;
import com.tradingstrat.config.LiveExecutorProperties;
import com.tradingstrat.indicator.Ta;
import com.tradingstrat.model.Candle;
import com.tradingstrat.model.ChartData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Runs the trend-overlay rule against the live market.
 *
 * <p>The rule: hold while price closes above {@code EMA(n) x (1 + band)}, go flat while it
 * closes below {@code EMA(n) x (1 - band)}, and keep the current position in between. The
 * dead zone is what stops price oscillating around the average from forcing a switch —
 * and therefore a fee — on every crossing.
 *
 * <p>Two properties of this implementation matter more than the code:
 *
 * <ol>
 *   <li><b>It reuses {@link Ta#ema}</b>, the same function the backtest used. Any live/backtest
 *       divergence would otherwise be impossible to attribute.</li>
 *   <li><b>It only ever reads closed bars.</b> The most recent candle is in progress and is
 *       discarded, which is the live equivalent of the lookahead protection in the backtest.</li>
 * </ol>
 *
 * <p>Runs a minute after each 4h boundary. Nothing happens unless {@code enabled} is set,
 * and no order is sent unless {@code dryRun} is also cleared.
 */
@Component
public class LiveExecutor {

    private static final Logger log = LoggerFactory.getLogger(LiveExecutor.class);

    private final ChartDataClient chartDataClient;
    private final BinanceTradingClient trading;
    private final PositionStore positions;
    private final LiveExecutorProperties props;

    public LiveExecutor(ChartDataClient chartDataClient,
                        BinanceTradingClient trading,
                        PositionStore positions,
                        LiveExecutorProperties props) {
        this.chartDataClient = chartDataClient;
        this.trading = trading;
        this.positions = positions;
        this.props = props;
    }

    /** State the configuration loudly at startup: silent live trading is the nightmare case. */
    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        if (!props.enabled()) {
            log.info("Live executor DISABLED (tradingstrat.live.enabled=false)");
            return;
        }
        if (props.dryRun()) {
            log.info("Live executor ENABLED in DRY RUN — decisions logged, no orders sent. "
                    + "symbols={} interval={} ema={} band={}%",
                    props.symbols(), props.interval(), props.emaLen(), props.bandPct());
        } else {
            log.warn("*** LIVE TRADING ARMED *** target={} ({}), symbols={}, {} {} per entry",
                    props.tradeBaseUrl(), props.isTestnet() ? "testnet" : "PRODUCTION",
                    props.symbols(), props.quotePerSymbol(), props.quoteAsset());
        }
        log.info("Current positions: {}", positions.all());
    }

    /**
     * A minute after each 4h close (00:00, 04:00, ... UTC), giving the exchange time to
     * finalise the candle before it is read.
     */
    @Scheduled(cron = "0 1 0,4,8,12,16,20 * * *", zone = "UTC")
    public void onBarClose() {
        if (!props.enabled()) {
            return;
        }
        for (String symbol : props.symbols()) {
            try {
                evaluate(symbol);
            } catch (Exception ex) {
                // One symbol failing must not stop the rest: a missed exit is the costly outcome.
                log.error("Live evaluation failed for {}: {}", symbol, ex.getMessage(), ex);
            }
        }
    }

    /**
     * What the rule says right now, for every configured symbol.
     *
     * Strictly read-only: no orders, no state writes, and it ignores {@code enabled}. This
     * is the "what would it do" check, safe to call at any time.
     */
    public List<Decision> preview() {
        return props.symbols().stream().map(symbol -> {
            try {
                return decide(symbol);
            } catch (Exception ex) {
                return Decision.failed(symbol, ex.getMessage());
            }
        }).toList();
    }

    /** One symbol's reading of the rule. */
    public record Decision(
            String symbol,
            String barCloseTime,
            Double price,
            Double ema,
            Double bandLower,
            Double bandUpper,
            String zone,
            Integer currentPosition,
            Integer desiredPosition,
            String action,
            String error
    ) {
        static Decision failed(String symbol, String error) {
            return new Decision(symbol, null, null, null, null, null, null, null, null, "ERROR", error);
        }
    }

    /** Read the rule for one symbol. No orders, no state changes. */
    private Decision decide(String symbol) {
        ChartData chart = chartDataClient.fetchHistory(symbol, props.interval(), props.klineBars());
        List<Candle> candles = chart.candles();
        if (candles.size() < props.emaLen() + 5) {
            return Decision.failed(symbol,
                    "only " + candles.size() + " candles, need " + (props.emaLen() + 5));
        }

        // Drop the final candle: it is the bar currently forming, and acting on it would
        // be deciding from a price that has not settled.
        List<Candle> closed = candles.subList(0, candles.size() - 1);
        double[] close = closed.stream().mapToDouble(Candle::close).toArray();

        double[] ema = Ta.ema(close, props.emaLen());
        int i = close.length - 1;
        if (Double.isNaN(ema[i])) {
            return Decision.failed(symbol, "EMA not warm");
        }

        double price = close[i];
        double upper = ema[i] * (1 + props.bandPct() / 100.0);
        double lower = ema[i] * (1 - props.bandPct() / 100.0);

        int current = positions.get(symbol);
        int desired = current;
        if (price > upper) {
            desired = 1;
        } else if (price < lower) {
            desired = 0;
        }

        String zone = price > upper ? "above band" : price < lower ? "below band" : "inside band";
        String action = desired == current ? "HOLD" : desired == 1 ? "BUY" : "SELL";

        return new Decision(symbol, closed.get(i).timestamp().toString(),
                price, ema[i], lower, upper, zone, current, desired, action, null);
    }

    /** Decide and, if the position changes, act. Package-private so it can be driven manually. */
    void evaluate(String symbol) {
        Decision d = decide(symbol);
        if (d.error() != null) {
            log.warn("{}: {}", symbol, d.error());
            return;
        }

        log.info("{} @ {}: close={} ema{}={} band=[{}, {}] {} -> position {} => {} ({})",
                d.symbol(), d.barCloseTime(), fmt(d.price()), props.emaLen(), fmt(d.ema()),
                fmt(d.bandLower()), fmt(d.bandUpper()), d.zone(),
                d.currentPosition(), d.desiredPosition(), d.action());

        if (d.desiredPosition().equals(d.currentPosition())) {
            return;
        }
        long barMillis = java.time.Instant.parse(d.barCloseTime()).toEpochMilli();
        if (d.desiredPosition() == 1) {
            enter(symbol, barMillis);
        } else {
            exit(symbol, barMillis);
        }
    }

    private void enter(String symbol, long barCloseMillis) {
        BigDecimal quote = BigDecimal.valueOf(props.quotePerSymbol());
        String clientOrderId = orderId(symbol, barCloseMillis, "BUY");

        if (props.dryRun()) {
            log.info("[DRY RUN] would BUY {} of {} (clientOrderId={})", quote, symbol, clientOrderId);
            positions.set(symbol, 1);
            return;
        }

        Map<String, Object> result = trading.marketBuy(symbol, quote, clientOrderId);
        log.warn("BUY {} {} -> {}", quote, symbol, result.get("status"));
        positions.set(symbol, 1);
    }

    private void exit(String symbol, long barCloseMillis) {
        String base = baseAsset(symbol);
        String clientOrderId = orderId(symbol, barCloseMillis, "SELL");

        if (props.dryRun()) {
            log.info("[DRY RUN] would SELL all {} of {} (clientOrderId={})", base, symbol, clientOrderId);
            positions.set(symbol, 0);
            return;
        }

        BinanceTradingClient.SymbolFilters filters = trading.filters(symbol);
        BigDecimal free = trading.freeBalance(base);
        BigDecimal quantity = filters.roundQuantity(free);

        if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
            // Nothing to sell: treat as already flat rather than retrying every bar.
            log.warn("{}: no sellable {} balance ({}); marking flat", symbol, base, free);
            positions.set(symbol, 0);
            return;
        }

        Map<String, Object> result = trading.marketSell(symbol, quantity, clientOrderId);
        log.warn("SELL {} {} -> {}", quantity, symbol, result.get("status"));
        positions.set(symbol, 0);
    }

    /**
     * Deterministic per symbol, bar and side, so replaying the same bar cannot double-fire:
     * the exchange rejects a duplicate client order id.
     */
    private String orderId(String symbol, long barCloseMillis, String side) {
        return "ovl-" + symbol + "-" + side.charAt(0) + "-" + barCloseMillis;
    }

    /** "BTCUSDT" with quote "USDT" gives base "BTC". */
    private String baseAsset(String symbol) {
        String quote = props.quoteAsset();
        return symbol.endsWith(quote) ? symbol.substring(0, symbol.length() - quote.length()) : symbol;
    }

    private String fmt(double v) {
        return String.format("%.6f", v);
    }
}
