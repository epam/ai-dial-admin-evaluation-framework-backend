package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

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
}
