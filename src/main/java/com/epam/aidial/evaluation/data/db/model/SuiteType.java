package com.epam.aidial.evaluation.data.db.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.stream.Collectors;

public enum SuiteType {
    DEPLOYMENT("DEPLOYMENT"),
    MCP_TOOL("MCP_TOOL");

    private final String value;

    SuiteType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static SuiteType fromValue(String value) {
        return Arrays.stream(values())
                .filter(t -> t.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid suite type: " + value + ". Valid values: "
                        + Arrays.stream(values()).map(SuiteType::getValue).collect(Collectors.joining(", "))));
    }
}
