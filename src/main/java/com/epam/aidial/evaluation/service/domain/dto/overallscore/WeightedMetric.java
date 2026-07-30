package com.epam.aidial.evaluation.service.domain.dto.overallscore;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * One row of a {@link WeightedMean} definition: {@code weight × avg(metric::metricName::outputField)}.
 * Not validated against the suite's actually-configured metrics at write time — a reference absent from a
 * given run's data resolves to a SQL {@code NULL} at computation time rather than failing suite
 * create/update.
 */
public record WeightedMetric(
        @NotBlank @Size(max = 255) @Schema(description = "TSMD name", example = "RAG Retrieval")
        String metricName,

        @NotBlank @Size(max = 255) @Schema(description = "Metric output field name", example = "F1")
        String outputField,

        @NotNull @Schema(description = "Weight applied to this metric's average", example = "1.0")
        BigDecimal weight) {}
