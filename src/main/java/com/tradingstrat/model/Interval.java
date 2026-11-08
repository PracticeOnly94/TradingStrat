package com.tradingstrat.model;

import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Supported chart intervals. Binance value is used directly in API calls.
 */
public enum Interval {

    FIFTEEN_MINUTES("15m"),
    ONE_HOUR("1h"),
    FOUR_HOURS("4h"),
    ONE_DAY("1d");

    private final String value;

    Interval(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /** Wall-clock length of one candle. Used to tell when a higher-timeframe bar has closed. */
    public Duration duration() {
        return switch (this) {
            case FIFTEEN_MINUTES -> Duration.ofMinutes(15);
            case ONE_HOUR -> Duration.ofHours(1);
            case FOUR_HOURS -> Duration.ofHours(4);
            case ONE_DAY -> Duration.ofDays(1);
        };
    }

    /**
     * Resolve an interval from its string representation (e.g. "1h", "4h").
     * Throws {@link IllegalArgumentException} if the value is not supported.
     */
    public static Interval fromString(String input) {
        for (Interval interval : values()) {
            if (interval.value.equalsIgnoreCase(input)) {
                return interval;
            }
        }
        String supported = Arrays.stream(values())
                .map(Interval::getValue)
                .collect(Collectors.joining(", "));
        throw new IllegalArgumentException(
                "Unsupported interval: \"" + input + "\". Supported values: " + supported);
    }
}
