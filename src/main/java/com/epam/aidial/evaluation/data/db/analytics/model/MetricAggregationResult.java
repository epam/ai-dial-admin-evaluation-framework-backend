package com.epam.aidial.evaluation.data.db.analytics.model;

public record MetricAggregationResult(
        String metricName, String outputName, Double avg, Double min, Double max, Long count) {}
