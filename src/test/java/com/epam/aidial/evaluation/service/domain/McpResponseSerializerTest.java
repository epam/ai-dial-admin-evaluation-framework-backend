package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.AudioContent;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.ResourceLink;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class McpResponseSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final McpResponseSerializer serializer = new McpResponseSerializer(objectMapper);

    @Test
    @DisplayName("Serializes text content")
    void serializeTextContent() throws Exception {
        TextContent text = new TextContent(null, "Hello world", null);
        CallToolResult result = new CallToolResult(List.of(text), false, null, null);

        String json = serializer.serialize(result);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("content").size()).isEqualTo(1);
        assertThat(node.get("content").get(0).get("type").asString()).isEqualTo("text");
        assertThat(node.get("content").get(0).get("text").asString()).isEqualTo("Hello world");
        assertThat(node.get("isError").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("Serializes isError true")
    void serializeIsErrorTrue() throws Exception {
        TextContent text = new TextContent(null, "Error occurred", null);
        CallToolResult result = new CallToolResult(List.of(text), true, null, null);

        String json = serializer.serialize(result);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("isError").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Serializes structuredContent when present")
    void serializeStructuredContent() throws Exception {
        Map<String, Object> structured = Map.of("results", List.of("a", "b"));
        CallToolResult result = new CallToolResult(List.of(), false, structured, null);

        String json = serializer.serialize(result);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.has("structuredContent")).isTrue();
        assertThat(node.get("structuredContent").get("results").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("Omits structuredContent when null")
    void omitNullStructuredContent() throws Exception {
        CallToolResult result = new CallToolResult(List.of(), false, null, null);

        String json = serializer.serialize(result);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.has("structuredContent")).isFalse();
    }

    @Test
    @DisplayName("Serializes mixed content types")
    void serializeMixedContent() throws Exception {
        TextContent text = new TextContent(null, "description", null);
        ImageContent image = new ImageContent(null, "base64data", "image/png", null);
        CallToolResult result = new CallToolResult(List.of(text, image), false, null, null);

        String json = serializer.serialize(result);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("content").size()).isEqualTo(2);
        assertThat(node.get("content").get(0).get("type").asString()).isEqualTo("text");
        assertThat(node.get("content").get(1).get("type").asString()).isEqualTo("image");
        assertThat(node.get("content").get(1).get("mimeType").asString()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("Returns null for null result")
    void nullResult() throws Exception {
        assertThat(serializer.serialize(null)).isNull();
    }

    @Test
    @DisplayName("Empty content list serializes to empty array")
    void emptyContentList() throws Exception {
        CallToolResult result = new CallToolResult(List.of(), false, null, null);

        String json = serializer.serialize(result);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("content").size()).isEqualTo(0);
    }

    @Test
    @DisplayName("Serializes audio content")
    void serializeAudioContent() throws Exception {
        AudioContent audio = new AudioContent(null, "audioBase64Data", "audio/wav");
        CallToolResult result = new CallToolResult(List.of(audio), false, null, null);

        String json = serializer.serialize(result);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("content").size()).isEqualTo(1);
        assertThat(node.get("content").get(0).get("type").asString()).isEqualTo("audio");
        assertThat(node.get("content").get(0).get("data").asString()).isEqualTo("audioBase64Data");
        assertThat(node.get("content").get(0).get("mimeType").asString()).isEqualTo("audio/wav");
    }

    @Test
    @DisplayName("Serializes embedded resource content")
    void serializeEmbeddedResource() throws Exception {
        TextResourceContents resource = new TextResourceContents("file:///test.txt", "text/plain", "file contents");
        EmbeddedResource embedded = new EmbeddedResource(null, resource);
        CallToolResult result = new CallToolResult(List.of(embedded), false, null, null);

        String json = serializer.serialize(result);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("content").size()).isEqualTo(1);
        assertThat(node.get("content").get(0).get("type").asString()).isEqualTo("resource");
        assertThat(node.get("content").get(0).has("resource")).isTrue();
        assertThat(node.get("content").get(0).get("resource").get("uri").asString())
                .isEqualTo("file:///test.txt");
    }

    @Test
    @DisplayName("Serializes resource link content")
    void serializeResourceLink() throws Exception {
        ResourceLink link = new ResourceLink("test-resource", null, "file:///data.csv", null, null, null, null, null);
        CallToolResult result = new CallToolResult(List.of(link), false, null, null);

        String json = serializer.serialize(result);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("content").size()).isEqualTo(1);
        assertThat(node.get("content").get(0).get("type").asString()).isEqualTo("resource_link");
        assertThat(node.get("content").get(0).get("uri").asString()).isEqualTo("file:///data.csv");
        assertThat(node.get("content").get(0).get("name").asString()).isEqualTo("test-resource");
    }

    @Test
    @DisplayName("Serializes null content list as empty array")
    void nullContentList() throws Exception {
        CallToolResult result = new CallToolResult(null, false, null, null);

        String json = serializer.serialize(result);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("content").size()).isEqualTo(0);
    }
}
