package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.service.domain.RequestSpec;
import com.epam.aidial.evaluation.service.domain.dto.ChainRequestType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ChainStepExecutorRegistry")
class ChainStepExecutorRegistryTest {

    @Test
    @DisplayName("resolves the HTTP executor for an HTTP element")
    void resolvesHttp() {
        StubExecutor http = new StubExecutor(ChainRequestType.HTTP);

        assertThat(new ChainStepExecutorRegistry(List.of(http)).require(ChainRequestType.HTTP))
                .isSameAs(http);
    }

    @Test
    @DisplayName("a null type resolves to HTTP, matching the DTO's absent-discriminator default")
    void nullTypeResolvesToHttp() {
        StubExecutor http = new StubExecutor(ChainRequestType.HTTP);

        assertThat(new ChainStepExecutorRegistry(List.of(http)).require(null)).isSameAs(http);
    }

    @Test
    @DisplayName("two executors for the same type are rejected at construction, not resolved by bean ordering")
    void duplicateRegistrationRejected() {
        List<ChainStepExecutor> duplicates =
                List.of(new StubExecutor(ChainRequestType.HTTP), new StubExecutor(ChainRequestType.HTTP));

        assertThatThrownBy(() -> new ChainStepExecutorRegistry(duplicates))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate ChainStepExecutor")
                .hasMessageContaining("HTTP");
    }

    @Test
    @DisplayName("an unregistered type fails loudly rather than returning null")
    void unregisteredTypeThrows() {
        ChainStepExecutorRegistry registry =
                new ChainStepExecutorRegistry(List.of(new StubExecutor(ChainRequestType.HTTP)));

        assertThatThrownBy(() -> registry.require(ChainRequestType.MCP_TOOL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP_TOOL");
    }

    @Test
    @DisplayName("the real MCP executor is registered for MCP_TOOL but throws UnsupportedOperationException")
    void mcpStubIsRegisteredAndThrows() {
        ChainStepExecutorRegistry registry = new ChainStepExecutorRegistry(
                List.of(new StubExecutor(ChainRequestType.HTTP), new McpChainStepExecutor()));

        ChainStepExecutor mcp = registry.require(ChainRequestType.MCP_TOOL);

        assertThat(mcp).isInstanceOf(McpChainStepExecutor.class);
        assertThatThrownBy(() -> mcp.execute(new ChainStepRequest(
                        new RequestSpec(1, "tool", ChainRequestType.MCP_TOOL, null, null, List.of(), List.of()),
                        null,
                        java.util.Map.of(),
                        java.util.Map.of())))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("MCP chaining is not supported");
    }

    private record StubExecutor(ChainRequestType supportedType) implements ChainStepExecutor {
        @Override
        public ChainStepOutcome execute(ChainStepRequest step) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
