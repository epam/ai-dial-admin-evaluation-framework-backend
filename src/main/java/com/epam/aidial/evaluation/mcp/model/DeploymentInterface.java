package com.epam.aidial.evaluation.mcp.model;

import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * MCP-owned mirror of {@code client.dialcore.dto.InterfaceType}'s wire values, used by
 * {@code list_deployments}'s {@code interfaceType} filter and the {@code interfaces} field of the
 * MCP deployment summary/detail shapes (D-F8).
 */
public enum DeploymentInterface {
    CHAT("chat"),
    EMBEDDING("embedding"),
    MCP("mcp"),
    CUSTOM_UI("custom_ui"),
    OPEN_AI_CHAT_COMPLETIONS("openaiChatCompletions"),
    OPEN_AI_RESPONSES("openaiResponses"),
    OPEN_AI_EMBEDDINGS("openaiEmbeddings"),
    ANTHROPIC_MESSAGES("anthropicMessages");

    private final String wireValue;

    DeploymentInterface(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String getWireValue() {
        return wireValue;
    }

    /**
     * Parses a wire value (e.g. {@code "openaiChatCompletions"}), never a Java constant name
     * (e.g. {@code "OPEN_AI_CHAT_COMPLETIONS"}). Throws {@link ValidationException} listing every
     * accepted value so the message can be surfaced to the caller verbatim via the structured
     * tool error contract.
     */
    public static DeploymentInterface fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(interfaceType -> interfaceType.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new ValidationException(
                        "Invalid interface type: '" + value + "'. Accepted values: " + acceptedValues()));
    }

    private static String acceptedValues() {
        return Arrays.stream(values()).map(DeploymentInterface::getWireValue).collect(Collectors.joining(", "));
    }
}
