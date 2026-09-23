package com.epam.aidial.evaluation.mcp.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("McpToolExecutor")
class McpToolExecutorTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final McpCallerContext callerContext = new McpCallerContext();
    private final McpToolExecutor executor = new McpToolExecutor(
            callerContext, new McpToolErrorTranslator(objectMapper), new McpToolResults(objectMapper));

    @Test
    @DisplayName("execute(Supplier) encodes the body's result as a successful tool result")
    void executeSupplierEncodesBodyResult() {
        CallToolResult result = executor.execute(() -> new Payload("ok"));

        assertThat(result.isError()).isFalse();
        JsonNode node = objectMapper.readTree(onlyTextContent(result).text());
        assertThat(node.get("status").asString()).isEqualTo("ok");
    }

    @Test
    @DisplayName("execute(Supplier) translates EntityNotFoundException into a NOT_FOUND tool error result")
    void executeSupplierTranslatesEntityNotFoundExceptionToNotFoundResult() {
        CallToolResult result = executor.execute(() -> {
            throw new EntityNotFoundException("Deployment not found: abc");
        });

        assertThat(result.isError()).isTrue();
        JsonNode node = objectMapper.readTree(onlyTextContent(result).text());
        assertThat(node.get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(node.get("message").asString()).isEqualTo("Deployment not found: abc");
    }

    @Test
    @DisplayName("execute(Function) passes the executor's own McpCallerContext instance to the body")
    void executeFunctionReceivesTheCallerContext() {
        AtomicReference<McpCallerContext> observed = new AtomicReference<>();

        CallToolResult result = executor.execute(ctx -> {
            observed.set(ctx);
            return new Payload("ok");
        });

        assertThat(result.isError()).isFalse();
        assertThat(observed.get()).isSameAs(callerContext);
    }

    @Test
    @DisplayName("execute(Function) translates a RuntimeException raised by the body")
    void executeFunctionTranslatesExceptionFromBody() {
        CallToolResult result = executor.execute(ctx -> {
            throw new EntityNotFoundException("Suite not found: xyz");
        });

        assertThat(result.isError()).isTrue();
        JsonNode node = objectMapper.readTree(onlyTextContent(result).text());
        assertThat(node.get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("execute(Supplier) never lets a translator RuntimeException escape, and returns an"
            + " INTERNAL_ERROR result that does not leak the translator failure's message")
    void executeSupplierSwallowsTranslatorFailureAndReturnsInternalError() {
        McpToolErrorTranslator failingTranslator = mock(McpToolErrorTranslator.class);
        when(failingTranslator.translate(any())).thenThrow(new RuntimeException("translator broke"));
        McpToolExecutor executorWithFailingTranslator =
                new McpToolExecutor(callerContext, failingTranslator, new McpToolResults(objectMapper));

        CallToolResult result = executorWithFailingTranslator.execute(() -> {
            throw new IllegalStateException("body failed");
        });

        assertThat(result.isError()).isTrue();
        JsonNode node = objectMapper.readTree(onlyTextContent(result).text());
        assertThat(node.get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(node.get("message").asString()).doesNotContain("translator broke");
    }

    private static TextContent onlyTextContent(CallToolResult result) {
        List<Content> content = result.content();
        assertThat(content).hasSize(1);
        return (TextContent) content.get(0);
    }

    private record Payload(String status) {}
}
