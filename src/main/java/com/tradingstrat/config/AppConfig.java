package com.tradingstrat.config;

import com.tradingstrat.algorithm.TradingAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration
@org.springframework.scheduling.annotation.EnableScheduling
@EnableConfigurationProperties({
        ChartApiProperties.class,
        ConfluenceProperties.class,
        SupportResistanceProperties.class,
        MomentumProperties.class,
        MeasuredEdgeProperties.class,
        RangeRsiProperties.class,
        LiveExecutorProperties.class
})
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /**
     * The strategy selected by {@code tradingstrat.algorithm}, marked primary so that
     * live analysis and the backtester resolve the same one.
     */
    @Bean
    @Primary
    public TradingAlgorithm activeTradingAlgorithm(
            List<TradingAlgorithm> algorithms,
            @Value("${tradingstrat.algorithm:cipher-b-confluence}") String key) {

        Map<String, TradingAlgorithm> byKey = algorithms.stream()
                .collect(Collectors.toMap(TradingAlgorithm::key, Function.identity()));

        TradingAlgorithm selected = byKey.get(key);
        if (selected == null) {
            throw new IllegalStateException(
                    "Unknown tradingstrat.algorithm '" + key + "'. Available: " + byKey.keySet());
        }
        log.info("Active trading algorithm: {}", key);
        return selected;
    }

    /**
     * RestTemplate for HTTP chart API clients.
     * Timeout is driven by {@code tradingstrat.chart-api.timeout-ms}.
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder, ChartApiProperties properties) {
        Duration timeout = Duration.ofMillis(properties.timeoutMs());
        return builder
                .setConnectTimeout(timeout)
                .setReadTimeout(timeout)
                .build();
    }
}
