package com.epam.aidial.evaluation.runner.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * {@code overall = Σ(weight × metric) / Σweight} over an explicit metric/weight list, mirroring the UI's
 * metric/weight table 1:1. Weights need not already sum to 1 — the division normalizes them regardless.
 * Duplicate {@code (metricName, outputField)} entries are allowed and combine via ordinary arithmetic
 * (equivalent to a single entry with the summed weight).
 */
public record WeightedMean(
        @NotEmpty @Valid @Schema(description = "Metric/weight rows, one per UI table row")
        List<WeightedMetric> weights) implements OverallScoreDefinition {}
