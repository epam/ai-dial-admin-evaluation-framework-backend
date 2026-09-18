package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.util.TracingConstants;
import lombok.Getter;

/**
 * The two dial-adas cost query phases, typed so {@link AdasCostQueryBuilder} and {@link CostService}
 * cannot be handed an arbitrary string when building the hand-rolled SQL/{@code eval.phase} baggage
 * predicates. {@link #getValue()} carries the exact {@link TracingConstants} wire value each phase
 * corresponds to on the outbound OTel baggage.
 */
@Getter
public enum EvalPhase {
    EXECUTION(TracingConstants.PHASE_EXECUTION),
    METRIC_EVALUATION(TracingConstants.PHASE_METRIC_EVALUATION);

    private final String value;

    EvalPhase(String value) {
        this.value = value;
    }
}
