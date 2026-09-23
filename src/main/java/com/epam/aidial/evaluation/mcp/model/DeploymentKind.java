package com.epam.aidial.evaluation.mcp.model;

import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * MCP-owned mirror of {@code service.domain.dto.deployment.DeploymentType}'s wire values, used by
 * {@code list_deployments}'s {@code type} filter and the {@code type} field of the MCP deployment
 * summary/detail shapes (D-F8). Kept as its own enum (rather than reusing the service enum
 * directly) so the MCP tool layer owns its wire contract independently of the REST-facing type.
 */
public enum DeploymentKind {
    DIAL_MODEL("dial-model"),
    DIAL_APPLICATION("dial-application"),
    DIAL_TOOLSET("dial-toolset");

    private final String wireValue;

    DeploymentKind(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String getWireValue() {
        return wireValue;
    }

    /**
     * Parses a wire value (e.g. {@code "dial-model"}), never a Java constant name (e.g.
     * {@code "DIAL_MODEL"}). Throws {@link ValidationException} listing every accepted value so
     * the message can be surfaced to the caller verbatim via the structured tool error contract.
     */
    public static DeploymentKind fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(kind -> kind.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new ValidationException(
                        "Invalid deployment type: '" + value + "'. Accepted values: " + acceptedValues()));
    }

    private static String acceptedValues() {
        return Arrays.stream(values()).map(DeploymentKind::getWireValue).collect(Collectors.joining(", "));
    }
}
