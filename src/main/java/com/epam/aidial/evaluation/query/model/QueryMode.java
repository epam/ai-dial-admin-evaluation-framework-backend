package com.epam.aidial.evaluation.query.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** Query mode (§2): row projection vs grouped aggregation. Wire values {@code row}/{@code aggregate}. */
public enum QueryMode {
    ROW("row"),
    AGGREGATE("aggregate");

    private final String code;

    QueryMode(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
