package com.epam.aidial.evaluation.query.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The allowlisted literal type set (§4.2) governing how a {@link ValueExpr}'s string {@code value}
 * is parsed. {@code NULL} represents SQL NULL and is only meaningful with {@code eq}/{@code ne}.
 * Wire values are the lowercase names. Extend deliberately.
 */
public enum ValueType {
    STRING("string"),
    INTEGER("integer"),
    LONG("long"),
    DECIMAL("decimal"),
    BOOLEAN("boolean"),
    DATE("date"),
    TIMESTAMP("timestamp"),
    UUID("uuid"),
    NULL("null");

    private final String code;

    ValueType(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }
}
