package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import io.modelcontextprotocol.spec.McpSchema.AudioContent;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.EmbeddedResource;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.ResourceLink;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Serializes MCP CallToolResult to a JSON string preserving the MCP envelope structure:
 * { "content": [...], "structuredContent": {...}, "isError": boolean }.
 *
 * <p>This format enables JSONata extraction using paths like:
 * $.content[0].text, $.structuredContent.results, $.isError
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class McpResponseSerializer {

    private final ObjectMapper objectMapper;

    /**
     * Serializes CallToolResult to JSON string.
     * Follows fail-fast convention: throws on serialization failures.
     */
    public String serialize(CallToolResult result) throws JacksonException {
        if (result == null) {
            return null;
        }
        ObjectNode root = objectMapper.createObjectNode();

        // Serialize content blocks
        ArrayNode contentArray = objectMapper.createArrayNode();
        List<Content> contents = result.content();
        if (contents != null) {
            for (Content content : contents) {
                contentArray.add(serializeContent(content));
            }
        }
        root.set("content", contentArray);

        // Serialize structuredContent if present
        Object structured = result.structuredContent();
        if (structured != null) {
            root.set("structuredContent", objectMapper.convertValue(structured, JsonNode.class));
        }

        // Serialize isError
        Boolean isError = result.isError();
        root.put("isError", isError != null && isError);

        return objectMapper.writeValueAsString(root);
    }

    private ObjectNode serializeContent(Content content) {
        ObjectNode node = objectMapper.createObjectNode();
        if (content instanceof TextContent text) {
            node.put("type", "text");
            node.put("text", text.text());
        } else if (content instanceof ImageContent image) {
            node.put("type", "image");
            node.put("data", image.data());
            node.put("mimeType", image.mimeType());
        } else if (content instanceof AudioContent audio) {
            node.put("type", "audio");
            node.put("data", audio.data());
            node.put("mimeType", audio.mimeType());
        } else if (content instanceof EmbeddedResource resource) {
            node.put("type", "resource");
            if (resource.resource() != null) {
                node.set("resource", objectMapper.convertValue(resource.resource(), JsonNode.class));
            }
        } else if (content instanceof ResourceLink link) {
            node.put("type", "resource_link");
            node.put("uri", link.uri());
            node.put("name", link.name());
        } else {
            node.put("type", "unknown");
            node.set("raw", objectMapper.convertValue(content, JsonNode.class));
        }
        return node;
    }
}
