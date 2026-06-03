package com.epam.aidial.evaluation.service.domain.dto.deployment;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Deployment type for path parameter and JSON discriminator.
 * Uses kebab-case in URL and JSON (e.g. "dial-model").
 */
@Getter
@RequiredArgsConstructor
public enum DeploymentType {
    DIAL_MODEL("dial-model"),
    DIAL_APPLICATION("dial-application"),
    DIAL_TOOLSET("dial-toolset");

    private final String value;

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static DeploymentType fromValue(String value) {
        return Arrays.stream(values())
                .filter(t -> t.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invalid deployment type: " + value + ". Valid values: "
                        + Arrays.stream(values()).map(DeploymentType::getValue).collect(Collectors.joining(", "))));
    }
}
