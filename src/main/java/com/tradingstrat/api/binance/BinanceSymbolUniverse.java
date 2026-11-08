package com.tradingstrat.api.binance;

import com.tradingstrat.api.SymbolUniverse;
import com.tradingstrat.config.ChartApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ranks Binance USDT pairs by 24h quote volume.
 *
 * Endpoint: GET /api/v3/ticker/24hr
 */
@Component
@Profile("!stub")
public class BinanceSymbolUniverse implements SymbolUniverse {

    private static final Logger log = LoggerFactory.getLogger(BinanceSymbolUniverse.class);

    /**
     * Pegged pairs. A stablecoin quoted against another stablecoin barely moves, so it
     * produces no signals and only dilutes the sample.
     */
    private static final Set<String> PEGGED = Set.of(
            "USDCUSDT", "FDUSDUSDT", "TUSDUSDT", "BUSDUSDT", "DAIUSDT", "USDPUSDT",
            "EURUSDT", "AEURUSDT", "GBPUSDT", "XUSDUSDT", "USD1USDT", "RLUSDUSDT", "UUSDT");

    /** Leveraged tokens decay by construction and do not track their underlying. */
    private static final Pattern LEVERAGED = Pattern.compile(".*(UP|DOWN|BULL|BEAR)USDT$");

    /** Below this, a 24h move is indistinguishable from a peg. */
    private static final double MIN_DAILY_MOVE_PCT = 0.05;

    private final RestTemplate restTemplate;
    private final ChartApiProperties properties;

    public BinanceSymbolUniverse(RestTemplate restTemplate, ChartApiProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    @Override
    public List<String> topByLiquidity(int limit) {
        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                properties.baseUrl() + "/api/v3/ticker/24hr",
                HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        List<Map<String, Object>> body = response.getBody();
        if (body == null) {
            throw new RestClientException("Binance returned an empty 24h ticker response");
        }

        List<String> symbols = body.stream()
                .filter(t -> String.valueOf(t.get("symbol")).endsWith("USDT"))
                .filter(t -> !PEGGED.contains(String.valueOf(t.get("symbol"))))
                .filter(t -> !LEVERAGED.matcher(String.valueOf(t.get("symbol"))).matches())
                .filter(t -> Math.abs(asDouble(t.get("priceChangePercent"))) >= MIN_DAILY_MOVE_PCT)
                .sorted(Comparator.comparingDouble(
                        (Map<String, Object> t) -> asDouble(t.get("quoteVolume"))).reversed())
                .limit(limit)
                .map(t -> String.valueOf(t.get("symbol")))
                .toList();

        log.info("Symbol universe ({} requested): {}", limit, symbols);
        return symbols;
    }

    private static double asDouble(Object value) {
        try {
            return value == null ? 0 : Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
