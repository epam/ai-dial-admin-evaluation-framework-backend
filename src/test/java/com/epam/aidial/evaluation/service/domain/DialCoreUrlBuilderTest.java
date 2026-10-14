package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.runner.service.DialCoreUrlBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DialCoreUrlBuilder")
class DialCoreUrlBuilderTest {

    private final DialCoreUrlBuilder urlBuilder = new DialCoreUrlBuilder();

    @Test
    @DisplayName("chat/completions routes to OpenAI standard path")
    void chatCompletionsRoutesToOpenAiPath() {
        String url = urlBuilder.buildUrl("gpt-4", "/chat/completions");
        assertThat(url).isEqualTo("/openai/deployments/gpt-4/chat/completions");
    }

    @Test
    @DisplayName("embeddings routes to OpenAI standard path")
    void embeddingsRoutesToOpenAiPath() {
        String url = urlBuilder.buildUrl("text-embedding", "/embeddings");
        assertThat(url).isEqualTo("/openai/deployments/text-embedding/embeddings");
    }

    @Test
    @DisplayName("custom path routes to v1 route path")
    void customPathRoutesToV1Route() {
        String url = urlBuilder.buildUrl("my-app", "/my-custom-endpoint");
        assertThat(url).isEqualTo("/v1/deployments/my-app/route/my-custom-endpoint");
    }

    @Test
    @DisplayName("path similar to standard but not exact routes to custom")
    void partialMatchRoutesToCustom() {
        String url = urlBuilder.buildUrl("gpt-4", "/chat/completions/stream");
        assertThat(url).isEqualTo("/v1/deployments/gpt-4/route/chat/completions/stream");
    }

    @Test
    @DisplayName("Responses path routes to itself without deployment id spliced in")
    void responsesRoutesToItself() {
        final String url = urlBuilder.buildUrl("gpt-4", "/openai/v1/responses");
        assertThat(url).isEqualTo("/openai/v1/responses");
    }

    @Test
    @DisplayName("Responses path preserves case-insensitive prefix routing")
    void responsesCaseVariantSubpathRoutesToItself() {
        final String url = urlBuilder.buildUrl("gpt-4", "/OPENAI/v1/RESPONSES/resp-1");
        assertThat(url).isEqualTo("/OPENAI/v1/RESPONSES/resp-1");
    }

    @Test
    @DisplayName("Anthropic Messages path routes to itself without deployment id spliced in")
    void anthropicMessagesRoutesToItself() {
        String url = urlBuilder.buildUrl("claude-3-5-sonnet", "/anthropic/v1/messages");
        assertThat(url).isEqualTo("/anthropic/v1/messages");
    }

    @Test
    @DisplayName("path similar to Anthropic Messages but not exact routes to custom")
    void anthropicMessagesNearMissRoutesToCustom() {
        String url = urlBuilder.buildUrl("claude-3-5-sonnet", "/anthropic/v1/messages/count_tokens");
        assertThat(url).isEqualTo("/v1/deployments/claude-3-5-sonnet/route/anthropic/v1/messages/count_tokens");
    }

    @Test
    @DisplayName("ROUTE path is invoked directly from the DIAL Core root")
    void routePathIsUnchanged() {
        String url = urlBuilder.buildUrl(SuiteType.ROUTE, null, "/validate/items/42");

        assertThat(url).isEqualTo("/validate/items/42");
    }

    @Test
    @DisplayName("ROUTE rejects a path without a leading slash")
    void routeRejectsUnrootedPath() {
        assertThatThrownBy(() -> urlBuilder.buildUrl(SuiteType.ROUTE, null, "validate/items"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("root-relative");
    }

    @Test
    @DisplayName("ROUTE rejects an authority-form path")
    void routeRejectsAuthorityPath() {
        assertThatThrownBy(() -> urlBuilder.buildUrl(SuiteType.ROUTE, null, "//external.example/path"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("root-relative");
    }

    @Test
    @DisplayName("ROUTE rejects query and fragment data embedded in the path")
    void routeRejectsEmbeddedQueryAndFragment() {
        assertThatThrownBy(() -> urlBuilder.buildUrl(SuiteType.ROUTE, null, "/validate?x=1"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("query or fragment");
        assertThatThrownBy(() -> urlBuilder.buildUrl(SuiteType.ROUTE, null, "/validate#part"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("query or fragment");
    }

    @Test
    @DisplayName("DEPLOYMENT suite-aware overload preserves existing URL routing")
    void deploymentSuiteAwareOverloadPreservesRouting() {
        String url = urlBuilder.buildUrl(SuiteType.DEPLOYMENT, "gpt-4", "/chat/completions");

        assertThat(url).isEqualTo("/openai/deployments/gpt-4/chat/completions");
    }
}
