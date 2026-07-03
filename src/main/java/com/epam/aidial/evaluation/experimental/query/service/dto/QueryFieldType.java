package com.epam.aidial.evaluation.experimental.query.service.dto;

import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Declared type of a flat schema field, aligned with the DSL literal vocabulary ({@link ValueType})
 * plus {@code OBJECT}/{@code ARRAY} for JSONB fields surfaced as-is (not flattened). Wire values
 * are the lowercase names.
 */
public enum QueryFieldType {
    STRING("string"),
    INTEGER("integer"),
    LONG("long"),
    DECIMAL("decimal"),
    BOOLEAN("boolean"),
    DATE("date"),
    TIMESTAMP("timestamp"),
    UUID("uuid"),
    OBJECT("object"),
    ARRAY("array");

    private final String code;

    QueryFieldType(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    /** Whether this type is a JSONB field surfaced as-is (an {@code object} or {@code array}). */
    public boolean isJsonb() {
        return this == OBJECT || this == ARRAY;
    }
}
