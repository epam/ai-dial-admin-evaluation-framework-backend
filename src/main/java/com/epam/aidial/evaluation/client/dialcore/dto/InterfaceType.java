package com.epam.aidial.evaluation.client.dialcore.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.stream.Collectors;

public enum InterfaceType {
    CHAT("chat"),
    EMBEDDING("embedding"),
    MCP("mcp"),
    CUSTOM_UI("custom_ui"),
    OPEN_AI_CHAT_COMPLETIONS("openaiChatCompletions"),
    OPEN_AI_RESPONSES("openaiResponses"),
    ANTHROPIC_MESSAGES("anthropicMessages");

    private final String value;

    InterfaceType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static InterfaceType fromValue(String value) {
        return Arrays.stream(values())
                .filter(t -> t.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid interface type: " + value + ". Valid values: "
                        + Arrays.stream(values()).map(InterfaceType::getValue).collect(Collectors.joining(", "))));
    }
}
