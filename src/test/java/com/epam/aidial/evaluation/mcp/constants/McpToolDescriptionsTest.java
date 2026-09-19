package com.epam.aidial.evaluation.mcp.constants;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("McpToolDescriptions")
class McpToolDescriptionsTest {

    @Test
    @DisplayName("UNSUPPORTED_FEATURES_SENTENCE equals the umbrella spec sentence literally")
    void unsupportedFeaturesSentenceMatchesUmbrellaSpecLiterally() {
        assertThat(McpToolDescriptions.UNSUPPORTED_FEATURES_SENTENCE)
                .isEqualTo("Not supported in this version: only single-request, single-turn DEPLOYMENT "
                        + "suites can be created and run.");
    }

    @Test
    @DisplayName("LIST_DEPLOYMENTS tells the agent to call get_deployment and recommends a chat or "
            + "completions interface")
    void listDeploymentsMentionsGetDeploymentAndChatOrCompletionsInterfaces() {
        assertThat(McpToolDescriptions.LIST_DEPLOYMENTS)
                .contains("get_deployment")
                .contains("chat")
                .contains("completions");
    }
}
