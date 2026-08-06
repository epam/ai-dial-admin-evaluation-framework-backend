package com.epam.aidial.evaluation.runner.dto.overallscore;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A suite's run-level {@code overall} metric-score definition. Discriminated by {@code type}:
 *
 * <ul>
 *   <li>{@link Mean} — no parameters; resolved at Phase 3 against whatever numeric metric fields the run
 *       currently has.
 *   <li>{@link WeightedMean} — an explicit {@code {metricName, outputField, weight}} list, composed into
 *       {@code Σ(weight × avg(metric)) / Σweight}.
 *   <li>{@link CustomFunction} — a self-contained Structured Query DSL expression (the free-form escape
 *       hatch for any other catalog function, e.g. {@code roc_auc}).
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Mean.class, name = "mean"),
    @JsonSubTypes.Type(value = WeightedMean.class, name = "weighted_mean"),
    @JsonSubTypes.Type(value = CustomFunction.class, name = "custom_function")
})
@Schema(
        description = "Polymorphic overall-score definition. Discriminated by `type`.",
        discriminatorProperty = "type",
        subTypes = {Mean.class, WeightedMean.class, CustomFunction.class})
public sealed interface OverallScoreDefinition permits Mean, WeightedMean, CustomFunction {}
