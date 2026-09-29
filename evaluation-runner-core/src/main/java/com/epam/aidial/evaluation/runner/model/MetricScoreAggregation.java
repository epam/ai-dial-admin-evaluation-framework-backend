package com.epam.aidial.evaluation.runner.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Which per-test-case leaf of the aggregated metric scores ({@code avg}, {@code min} or {@code max}) is read
 * when scores and built-in statistics are computed over the aggregated metrics table.
 */
public enum MetricScoreAggregation {
    AVG("avg"),
    MIN("min"),
    MAX("max");

    private final String leaf;

    MetricScoreAggregation(String leaf) {
        this.leaf = leaf;
    }

    /** Leaf key inside the aggregated {@code metric_scores} JSONB entry. */
    public String leaf() {
        return leaf;
    }

    @JsonValue
    public String getValue() {
        return name();
    }

    /** Null-safe: a missing value resolves to the default, {@link #AVG}. */
    public static MetricScoreAggregation orDefault(MetricScoreAggregation value) {
        return value != null ? value : AVG;
    }

    @JsonCreator
    public static MetricScoreAggregation fromValue(String value) {
        return Arrays.stream(values())
                .filter(a -> a.name().equals(value == null ? null : value.toUpperCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid metric score aggregation: " + value
                        + ". Valid values: "
                        + Arrays.stream(values())
                                .map(MetricScoreAggregation::getValue)
                                .collect(Collectors.joining(", "))));
    }
}
