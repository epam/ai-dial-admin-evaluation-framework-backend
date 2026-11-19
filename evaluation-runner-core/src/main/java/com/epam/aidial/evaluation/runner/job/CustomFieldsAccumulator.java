package com.epam.aidial.evaluation.runner.job;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Accumulates custom-fields deltas with recursively applied array merge semantics. */
final class CustomFieldsAccumulator {

    private final JsonObjectDeltaAccumulator accumulator;

    CustomFieldsAccumulator() {
        this(ArrayMergeMode.APPEND);
    }

    CustomFieldsAccumulator(ArrayMergeMode arrayMode) {
        this.accumulator = new JsonObjectDeltaAccumulator(arrayMode);
    }

    void accumulate(JsonNode customFields) {
        accumulator.accumulate(customFields);
    }

    ObjectNode getMerged() {
        return accumulator.getMerged();
    }
}
