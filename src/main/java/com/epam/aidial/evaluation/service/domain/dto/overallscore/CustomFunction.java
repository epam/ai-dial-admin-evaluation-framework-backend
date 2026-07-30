package com.epam.aidial.evaluation.service.domain.dto.overallscore;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * A self-contained Structured Query DSL expression over the configured metric columns
 * ({@code metric::<metricName>::<outputField>}), run with only the run-scoping params ({@code :runId},
 * {@code :computationId}). Stored opaquely and not validated as a runnable query at write time — the
 * free-form escape hatch for any catalog function not covered by {@link Mean}/{@link WeightedMean} (e.g.
 * {@code roc_auc}).
 */
public record CustomFunction(@NotNull Map<String, Object> expression) implements OverallScoreDefinition {}
