package com.epam.aidial.evaluation.client.dialcore.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.stream.Collectors;

public enum DialTransport {
    HTTP("HTTP"),
    SSE("SSE");

    private final String value;

    DialTransport(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static DialTransport fromValue(String value) {
        return Arrays.stream(values())
                .filter(t -> t.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid DIAL transport: " + value + ". Valid values: "
                        + Arrays.stream(values()).map(DialTransport::getValue).collect(Collectors.joining(", "))));
    }
}
