package com.tradingstrat.live;

import com.tradingstrat.config.LiveExecutorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Signed Binance Spot REST calls: account state, symbol filters, and order placement.
 *
 * <p>Every private endpoint needs the query string signed with HMAC-SHA256 over the API
 * secret, plus a {@code timestamp}. The key travels in the {@code X-MBX-APIKEY} header
 * and the secret never leaves this class.
 */
@Component
public class BinanceTradingClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceTradingClient.class);

    /** Tolerance for clock skew between this host and the exchange, in milliseconds. */
    private static final long RECV_WINDOW_MS = 5000;

    private final RestTemplate restTemplate;
    private final LiveExecutorProperties props;

    public BinanceTradingClient(RestTemplate restTemplate, LiveExecutorProperties props) {
        this.restTemplate = restTemplate;
        this.props = props;
    }

    /**
     * Trading rules for a symbol. Quantities that ignore these are rejected, and it is
     * the most common first-deployment failure.
     *
     * @param stepSize    quantity must be a whole multiple of this
     * @param minNotional an order's quantity x price must reach this
     */
    public record SymbolFilters(String symbol, BigDecimal stepSize, BigDecimal minNotional) {

        /** Round a quantity down onto the step grid — never up, which would overspend. */
        public BigDecimal roundQuantity(BigDecimal quantity) {
            if (stepSize.compareTo(BigDecimal.ZERO) <= 0) {
                return quantity;
            }
            return quantity.divide(stepSize, 0, RoundingMode.DOWN).multiply(stepSize).stripTrailingZeros();
        }
    }

    /** LOT_SIZE and NOTIONAL filters for one symbol. */
    @SuppressWarnings("unchecked")
    public SymbolFilters filters(String symbol) {
        Map<String, Object> body = getPublic("/api/v3/exchangeInfo?symbol=" + symbol);
        List<Map<String, Object>> symbols = (List<Map<String, Object>>) body.get("symbols");
        if (symbols == null || symbols.isEmpty()) {
            throw new IllegalStateException("No exchangeInfo for " + symbol);
        }

        BigDecimal stepSize = BigDecimal.ONE;
        BigDecimal minNotional = BigDecimal.ZERO;
        for (Map<String, Object> filter : (List<Map<String, Object>>) symbols.get(0).get("filters")) {
            String type = String.valueOf(filter.get("filterType"));
            if ("LOT_SIZE".equals(type)) {
                stepSize = new BigDecimal(String.valueOf(filter.get("stepSize")));
            } else if ("NOTIONAL".equals(type) || "MIN_NOTIONAL".equals(type)) {
                Object min = filter.get("minNotional");
                if (min != null) {
                    minNotional = new BigDecimal(String.valueOf(min));
                }
            }
        }
        return new SymbolFilters(symbol, stepSize, minNotional);
    }

    /** Free (unreserved) balance of one asset. */
    @SuppressWarnings("unchecked")
    public BigDecimal freeBalance(String asset) {
        Map<String, Object> account = signedRequest(HttpMethod.GET, "/api/v3/account", "");
        for (Map<String, Object> balance : (List<Map<String, Object>>) account.get("balances")) {
            if (asset.equalsIgnoreCase(String.valueOf(balance.get("asset")))) {
                return new BigDecimal(String.valueOf(balance.get("free")));
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * Market buy for a fixed amount of quote currency.
     *
     * Spending a quote amount rather than a base quantity sidesteps LOT_SIZE rounding
     * entirely on the way in — the exchange works out the quantity.
     */
    public Map<String, Object> marketBuy(String symbol, BigDecimal quoteAmount, String clientOrderId) {
        String params = "symbol=" + symbol
                + "&side=BUY&type=MARKET"
                + "&quoteOrderQty=" + quoteAmount.toPlainString()
                + "&newClientOrderId=" + clientOrderId;
        return signedRequest(HttpMethod.POST, "/api/v3/order", params);
    }

    /** Market sell of a base-asset quantity, which must already be step-rounded. */
    public Map<String, Object> marketSell(String symbol, BigDecimal quantity, String clientOrderId) {
        String params = "symbol=" + symbol
                + "&side=SELL&type=MARKET"
                + "&quantity=" + quantity.toPlainString()
                + "&newClientOrderId=" + clientOrderId;
        return signedRequest(HttpMethod.POST, "/api/v3/order", params);
    }

    private Map<String, Object> getPublic(String path) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                props.tradeBaseUrl() + path, HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});
        return response.getBody() == null ? Map.of() : response.getBody();
    }

    private Map<String, Object> signedRequest(HttpMethod method, String path, String params) {
        String query = params.isBlank() ? "" : params + "&";
        query += "timestamp=" + System.currentTimeMillis() + "&recvWindow=" + RECV_WINDOW_MS;
        String signed = query + "&signature=" + sign(query);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-MBX-APIKEY", props.apiKey());

        String url = props.tradeBaseUrl() + path + "?" + signed;
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, method, new HttpEntity<>(headers), new ParameterizedTypeReference<>() {});
        return response.getBody() == null ? Map.of() : response.getBody();
    }

    private String sign(String query) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    props.apiSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(query.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            // Never log the secret or the signature input.
            log.error("Failed to sign request: {}", ex.getMessage());
            throw new IllegalStateException("Request signing failed", ex);
        }
    }
}
