package com.epam.aidial.evaluation.service.domain.dto.overallscore;

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
        @NotBlank @Size(max = 255) String metricName,

        @NotBlank @Size(max = 255) String outputField,

        @NotNull BigDecimal weight) {}
