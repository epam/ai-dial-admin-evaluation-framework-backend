package com.epam.aidial.evaluation.mcp.support;

import com.epam.aidial.evaluation.mcp.model.McpErrorCode;
import com.epam.aidial.evaluation.mcp.model.McpToolError;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Encodes tool results into the MCP structured contract: a success result carries exactly one
 * {@code text} content block whose text is the JSON payload; an error result carries exactly one
 * {@code text} content block whose text is the JSON {@link McpToolError}. See
 * {@code docs/patterns/mcp-server.md} and design decision D-F5.
 */
@Component
@LogExecution
@RequiredArgsConstructor
@Slf4j
public class McpToolResults {

    private final JsonMapper objectMapper;

    /**
     * Encodes a successful tool result. A payload that cannot be serialized never propagates as
     * an exception — it is logged and turned into an {@link McpErrorCode#INTERNAL_ERROR} error
     * result instead, so a tool method built on this class never throws.
     */
    public CallToolResult success(Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            log.error("Failed to serialize MCP tool success payload of type {}", payloadType(payload), e);
            return error(new McpToolError(McpErrorCode.INTERNAL_ERROR, "Unexpected server error", null));
        }
        return CallToolResult.builder().addTextContent(json).isError(false).build();
    }

    /**
     * Encodes an error tool result. {@link McpToolError} is a fixed shape of an enum, a string and
     * a list of strings, so it is always serializable in practice; the {@code catch} still guards
     * against a broken {@link JsonMapper} configuration, so this method — like {@link #success},
     * per the "tools never throw" contract — never propagates an exception.
     */
    public CallToolResult error(McpToolError toolError) {
        String json;
        try {
            json = objectMapper.writeValueAsString(toolError);
        } catch (JacksonException e) {
            log.error("Failed to serialize MCP tool error payload with code {}", toolError.code(), e);
            json = fallbackInternalErrorJson();
        }
        return CallToolResult.builder().addTextContent(json).isError(true).build();
    }

    /**
     * Hand-built fallback for when {@link #error} itself cannot serialize a {@link McpToolError}.
     * Built as an {@link ObjectNode} rather than string concatenation, and never delegates back to
     * {@link #error}, so a broken serializer cannot cause a loop.
     */
    private String fallbackInternalErrorJson() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("code", McpErrorCode.INTERNAL_ERROR.name());
        node.put("message", "Unexpected server error");
        return node.toString();
    }

    private static String payloadType(Object payload) {
        return payload == null ? "null" : payload.getClass().getName();
    }
}
