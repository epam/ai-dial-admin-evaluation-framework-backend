package com.epam.aidial.evaluation.experimental.query.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** Logical operators (§3). Wire values {@code and}/{@code or}/{@code not}. */
public enum LogicalOp {
    AND("and"),
    OR("or"),
    NOT("not");

    private final String code;

    LogicalOp(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
