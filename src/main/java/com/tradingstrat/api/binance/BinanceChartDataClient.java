package com.tradingstrat.api.binance;

import com.tradingstrat.api.ChartDataClient;
import com.tradingstrat.config.ChartApiProperties;
import com.tradingstrat.model.Candle;
import com.tradingstrat.model.ChartData;
import com.tradingstrat.model.Interval;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches candlestick data from the Binance public REST API.
 * No API key required for market data.
 *
 * Endpoint: GET /api/v3/klines
 * Docs: https://binance-docs.github.io/apidocs/spot/en/#kline-candlestick-data
 */
@Component
@Profile("!stub")
public class BinanceChartDataClient implements ChartDataClient {

    // Binance kline array indices
    private static final int IDX_OPEN_TIME  = 0;
    private static final int IDX_OPEN       = 1;
    private static final int IDX_HIGH       = 2;
    private static final int IDX_LOW        = 3;
    private static final int IDX_CLOSE      = 4;
    private static final int IDX_VOLUME     = 5;

    private final RestTemplate restTemplate;
    private final ChartApiProperties properties;

    public BinanceChartDataClient(RestTemplate restTemplate, ChartApiProperties properties) {
        this.restTemplate = restTemplate;
        this.properties = properties;
    }

    /** Binance caps a single klines request at 1000 candles. */
    private static final int MAX_PER_REQUEST = 1000;

    @Override
    public ChartData fetchChart(String symbol, String interval) {
        Interval validated = Interval.fromString(interval);
        List<Candle> candles = fetchPage(symbol, validated, properties.defaultLimit(), null);
        return new ChartData(symbol.toUpperCase(), validated.getValue(), candles);
    }

    @Override
    public ChartData fetchHistory(String symbol, String interval, int bars, Instant end) {
        Interval validated = Interval.fromString(interval);
        List<Candle> all = new ArrayList<>(bars);
        Long endTime = end == null ? null : end.toEpochMilli();

        // Page backwards: each request returns the window ending at endTime, so the
        // oldest candle of a page sets the endTime for the next one.
        while (all.size() < bars) {
            int limit = Math.min(MAX_PER_REQUEST, bars - all.size());
            List<Candle> page = fetchPage(symbol, validated, limit, endTime);
            if (page.isEmpty()) {
                break;
            }
            all.addAll(0, page);
            endTime = page.get(0).timestamp().toEpochMilli() - 1;
            if (page.size() < limit) {
                break;   // provider has no more history
            }
        }

        return new ChartData(symbol.toUpperCase(), validated.getValue(), all);
    }

    /** One klines request. {@code endTime} null means "most recent". */
    private List<Candle> fetchPage(String symbol, Interval interval, int limit, Long endTime) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(properties.baseUrl() + "/api/v3/klines")
                .queryParam("symbol", symbol.toUpperCase())
                .queryParam("interval", interval.getValue())
                .queryParam("limit", limit);
        if (endTime != null) {
            builder.queryParam("endTime", endTime);
        }

        ResponseEntity<List<List<Object>>> response = restTemplate.exchange(
                builder.toUriString(),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {}
        );

        List<List<Object>> body = response.getBody();
        if (body == null) {
            throw new RestClientException(
                    "Binance returned an empty body for " + symbol + " " + interval.getValue());
        }

        return body.stream().map(this::mapToCandle).toList();
    }

    /**
     * Each Binance kline is a raw array:
     * [openTime, open, high, low, close, volume, closeTime, quoteVolume, trades, ...]
     * open/high/low/close/volume are Strings; openTime is a Long.
     */
    private Candle mapToCandle(List<Object> raw) {
        Instant timestamp = Instant.ofEpochMilli(((Number) raw.get(IDX_OPEN_TIME)).longValue());
        double open   = Double.parseDouble((String) raw.get(IDX_OPEN));
        double high   = Double.parseDouble((String) raw.get(IDX_HIGH));
        double low    = Double.parseDouble((String) raw.get(IDX_LOW));
        double close  = Double.parseDouble((String) raw.get(IDX_CLOSE));
        double volume = Double.parseDouble((String) raw.get(IDX_VOLUME));
        return new Candle(timestamp, open, high, low, close, volume);
    }
}
