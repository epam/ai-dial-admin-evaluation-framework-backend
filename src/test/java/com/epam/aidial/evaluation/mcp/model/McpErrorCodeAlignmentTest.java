package com.epam.aidial.evaluation.mcp.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.web.handler.ErrorCode;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every {@link McpErrorCode} except {@link McpErrorCode#NOT_SUPPORTED} has a
 * same-named counterpart in the REST {@link ErrorCode} vocabulary, per the umbrella spec's
 * "Where a code also exists in the REST ErrorCode vocabulary it SHALL carry the same meaning."
 * This test lives in test scope only, so it may reference {@code web} without violating the
 * {@code mcp} layer's outbound rules (D-F4).
 */
@DisplayName("McpErrorCode <-> web.handler.ErrorCode alignment")
class McpErrorCodeAlignmentTest {

    @Test
    @DisplayName("every McpErrorCode except NOT_SUPPORTED has a same-named ErrorCode constant")
    void everyMcpErrorCodeExceptNotSupportedExistsInWebErrorCode() {
        Set<String> restErrorCodeNames =
                Arrays.stream(ErrorCode.values()).map(Enum::name).collect(Collectors.toSet());

        Set<String> unaligned = Arrays.stream(McpErrorCode.values())
                .filter(code -> code != McpErrorCode.NOT_SUPPORTED)
                .map(Enum::name)
                .filter(name -> !restErrorCodeNames.contains(name))
                .collect(Collectors.toSet());

        assertThat(unaligned).isEmpty();
    }

    @Test
    @DisplayName("NOT_SUPPORTED has no REST counterpart")
    void notSupportedHasNoRestCounterpart() {
        Set<String> restErrorCodeNames =
                Arrays.stream(ErrorCode.values()).map(Enum::name).collect(Collectors.toSet());

        assertThat(restErrorCodeNames).doesNotContain(McpErrorCode.NOT_SUPPORTED.name());
    }
}
