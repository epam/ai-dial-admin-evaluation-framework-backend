package com.epam.aidial.evaluation.runner.client.mcp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.stream.Collectors;

public enum McpTransport {
    STREAMABLE_HTTP("streamable-http"),
    SSE("sse");

    private final String value;

    McpTransport(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static McpTransport fromValue(String value) {
        return Arrays.stream(values())
                .filter(t -> t.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid MCP transport: " + value + ". Valid values: "
                        + Arrays.stream(values()).map(McpTransport::getValue).collect(Collectors.joining(", "))));
    }
}
