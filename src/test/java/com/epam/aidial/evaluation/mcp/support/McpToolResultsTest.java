package com.epam.aidial.evaluation.mcp.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.mcp.model.McpErrorCode;
import com.epam.aidial.evaluation.mcp.model.McpToolError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

@DisplayName("McpToolResults")
class McpToolResultsTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();
    private final McpToolResults results = new McpToolResults(objectMapper);

    @Test
    @DisplayName("success() returns isError=false with exactly one TextContent block parsing to the payload")
    void successEncodesPayloadAsSingleTextBlock() {
        CallToolResult result = results.success(new Payload("alpha", "42"));

        assertThat(result.isError()).isFalse();
        JsonNode node = objectMapper.readTree(onlyTextContent(result).text());
        assertThat(node.get("name").asString()).isEqualTo("alpha");
        assertThat(node.get("value").asString()).isEqualTo("42");
    }

    @Test
    @DisplayName("error() returns isError=true with one text block carrying code, message and details")
    void errorEncodesToolErrorAsSingleTextBlock() {
        McpToolError toolError =
                new McpToolError(McpErrorCode.NOT_FOUND, "Deployment not found: abc", List.of("id: abc"));

        CallToolResult result = results.error(toolError);

        assertThat(result.isError()).isTrue();
        JsonNode node = objectMapper.readTree(onlyTextContent(result).text());
        assertThat(node.get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(node.get("message").asString()).isEqualTo("Deployment not found: abc");
        assertThat(node.get("details").get(0).asString()).isEqualTo("id: abc");
    }

    @Test
    @DisplayName("success() with an unserializable payload returns an INTERNAL_ERROR result instead of throwing")
    void successWithUnserializablePayloadReturnsInternalErrorResultInsteadOfThrowing() {
        CallToolResult result = results.success(new Explosive());

        assertThat(result.isError()).isTrue();
        JsonNode node = objectMapper.readTree(onlyTextContent(result).text());
        assertThat(node.get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(node.get("message").asString()).isEqualTo("Unexpected server error");
    }

    @Test
    @DisplayName("error() with a JsonMapper that cannot serialize returns a hand-built INTERNAL_ERROR "
            + "result instead of throwing")
    void errorWithBrokenJsonMapperReturnsHandBuiltInternalErrorResultInsteadOfThrowing() {
        JsonMapper brokenMapper = mock(JsonMapper.class);
        when(brokenMapper.writeValueAsString(any())).thenThrow(new JacksonException("boom") {});
        when(brokenMapper.createObjectNode()).thenReturn(JsonNodeFactory.instance.objectNode());
        McpToolResults brokenResults = new McpToolResults(brokenMapper);
        McpToolError toolError = new McpToolError(McpErrorCode.NOT_FOUND, "Deployment not found: abc", null);

        CallToolResult result = brokenResults.error(toolError);

        assertThat(result.isError()).isTrue();
        JsonNode node = objectMapper.readTree(onlyTextContent(result).text());
        assertThat(node.get("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(node.get("message").asString()).isEqualTo("Unexpected server error");
    }

    private static TextContent onlyTextContent(CallToolResult result) {
        List<Content> content = result.content();
        assertThat(content).hasSize(1);
        return (TextContent) content.get(0);
    }

    private record Payload(String name, String value) {}

    /** A bean whose getter always throws, so Jackson cannot serialize it (used to force a failure). */
    private static final class Explosive {
        public String getValue() {
            throw new IllegalStateException("boom");
        }
    }
}
