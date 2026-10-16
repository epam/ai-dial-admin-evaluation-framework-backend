package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.runner.model.target.DeploymentTarget;
import com.epam.aidial.evaluation.runner.model.target.RouteTarget;
import com.epam.aidial.evaluation.runner.service.DialCoreUrlBuilder;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("DialCoreUrlBuilder")
class DialCoreUrlBuilderTest {

    private final DialCoreUrlBuilder urlBuilder = new DialCoreUrlBuilder();

    @Test
    @DisplayName("chat/completions routes to OpenAI standard path")
    void chatCompletionsRoutesToOpenAiPath() {
        String url = urlBuilder.buildUrl(deployment("gpt-4"), "/chat/completions");
        assertThat(url).isEqualTo("/openai/deployments/gpt-4/chat/completions");
    }

    @Test
    @DisplayName("embeddings routes to OpenAI standard path")
    void embeddingsRoutesToOpenAiPath() {
        String url = urlBuilder.buildUrl(deployment("text-embedding"), "/embeddings");
        assertThat(url).isEqualTo("/openai/deployments/text-embedding/embeddings");
    }

    @Test
    @DisplayName("custom path routes to v1 route path")
    void customPathRoutesToV1Route() {
        String url = urlBuilder.buildUrl(deployment("my-app"), "/my-custom-endpoint");
        assertThat(url).isEqualTo("/v1/deployments/my-app/route/my-custom-endpoint");
    }

    @Test
    @DisplayName("path similar to standard but not exact routes to custom")
    void partialMatchRoutesToCustom() {
        String url = urlBuilder.buildUrl(deployment("gpt-4"), "/chat/completions/stream");
        assertThat(url).isEqualTo("/v1/deployments/gpt-4/route/chat/completions/stream");
    }

    @Test
    @DisplayName("Responses path routes to itself without deployment id spliced in")
    void responsesRoutesToItself() {
        final String url = urlBuilder.buildUrl(deployment("gpt-4"), "/openai/v1/responses");
        assertThat(url).isEqualTo("/openai/v1/responses");
    }

    @Test
    @DisplayName("Responses path preserves case-insensitive prefix routing")
    void responsesCaseVariantSubpathRoutesToItself() {
        final String url = urlBuilder.buildUrl(deployment("gpt-4"), "/OPENAI/v1/RESPONSES/resp-1");
        assertThat(url).isEqualTo("/OPENAI/v1/RESPONSES/resp-1");
    }

    @Test
    @DisplayName("Anthropic Messages path routes to itself without deployment id spliced in")
    void anthropicMessagesRoutesToItself() {
        String url = urlBuilder.buildUrl(deployment("claude-3-5-sonnet"), "/anthropic/v1/messages");
        assertThat(url).isEqualTo("/anthropic/v1/messages");
    }

    @Test
    @DisplayName("path similar to Anthropic Messages but not exact routes to custom")
    void anthropicMessagesNearMissRoutesToCustom() {
        String url = urlBuilder.buildUrl(deployment("claude-3-5-sonnet"), "/anthropic/v1/messages/count_tokens");
        assertThat(url).isEqualTo("/v1/deployments/claude-3-5-sonnet/route/anthropic/v1/messages/count_tokens");
    }

    @Test
    @DisplayName("ROUTE rejects a path without a leading slash")
    void routeRejectsUnrootedPath() {
        assertThatThrownBy(() -> urlBuilder.buildUrl(new RouteTarget(null), "validate/items"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("root-relative");
    }

    @Test
    @DisplayName("ROUTE path is invoked directly from the DIAL Core root")
    void routePathIsUnchanged() {
        assertThat(urlBuilder.buildUrl(new RouteTarget(null), "/validate/items/42"))
                .isEqualTo("/validate/items/42");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("invalidRoutePaths")
    @DisplayName("ROUTE rejects unsupported paths")
    void routeTargetRejectsInvalidPaths(String path, String expectedMessagePart) {
        assertThatThrownBy(() -> urlBuilder.buildUrl(new RouteTarget(null), path))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining(expectedMessagePart);
    }

    static Stream<Arguments> invalidRoutePaths() {
        return Stream.of(
                Arguments.of("/validate?x=1", "query or fragment"),
                Arguments.of("/validate#part", "query or fragment"),
                Arguments.of("//external.example/path", "root-relative"),
                Arguments.of("/validate\u0000x", "unsupported character"),
                Arguments.of("/validate\\x", "unsupported character"));
    }

    private static DeploymentTarget deployment(String id) {
        return new DeploymentTarget(DeploymentReferenceDto.builder().id(id).build());
    }
}
