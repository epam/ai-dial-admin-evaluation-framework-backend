package com.epam.aidial.evaluation.mcp.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.mcp.constants.McpToolDescriptions;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UnsupportedFeatureGuard")
class UnsupportedFeatureGuardTest {

    private final UnsupportedFeatureGuard guard = new UnsupportedFeatureGuard();

    @Test
    @DisplayName("rejectIfPresent() passes when value is null")
    void rejectIfPresentPassesWhenValueIsNull() {
        assertThatNoException().isThrownBy(() -> guard.rejectIfPresent("suiteType", null));
    }

    @Test
    @DisplayName("rejectIfPresent() throws with a message starting with the unsupported-features sentence "
            + "followed by the feature name when value is non-null")
    void rejectIfPresentThrowsWhenValueIsPresent() {
        assertThatThrownBy(() -> guard.rejectIfPresent("suiteType", "MCP_TOOL"))
                .isInstanceOf(UnsupportedFeatureException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).startsWith(McpToolDescriptions.UNSUPPORTED_FEATURES_SENTENCE);
                    assertThat(e.getMessage()).endsWith("suiteType");
                });
    }

    @Test
    @DisplayName("rejectIfNotEmpty() passes when value is null")
    void rejectIfNotEmptyPassesWhenValueIsNull() {
        assertThatNoException().isThrownBy(() -> guard.rejectIfNotEmpty("additionalRequests", null));
    }

    @Test
    @DisplayName("rejectIfNotEmpty() passes when value is empty")
    void rejectIfNotEmptyPassesWhenValueIsEmpty() {
        assertThatNoException().isThrownBy(() -> guard.rejectIfNotEmpty("additionalRequests", List.of()));
    }

    @Test
    @DisplayName("rejectIfNotEmpty() throws with a message starting with the unsupported-features sentence "
            + "followed by the feature name when value is non-empty")
    void rejectIfNotEmptyThrowsWhenValueIsNonEmpty() {
        assertThatThrownBy(() -> guard.rejectIfNotEmpty("additionalRequests", List.of("req-2")))
                .isInstanceOf(UnsupportedFeatureException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).startsWith(McpToolDescriptions.UNSUPPORTED_FEATURES_SENTENCE);
                    assertThat(e.getMessage()).endsWith("additionalRequests");
                });
    }
}
