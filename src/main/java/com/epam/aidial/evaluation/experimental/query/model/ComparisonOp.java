package com.epam.aidial.evaluation.experimental.query.model;

import com.fasterxml.jackson.annotation.JsonValue;

/** Comparison operator codes (§3). Wire values are the lowercase codes ({@code eq}, {@code ne}, …). */
public enum ComparisonOp {
    EQ("eq"),
    NE("ne"),
    CO("co"),
    NC("nc"),
    LT("lt"),
    GT("gt"),
    LE("le"),
    GE("ge"),
    IN("in");

    private final String code;

    ComparisonOp(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
