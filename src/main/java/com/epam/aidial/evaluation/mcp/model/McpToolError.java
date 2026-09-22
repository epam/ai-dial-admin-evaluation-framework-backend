package com.epam.aidial.evaluation.mcp.model;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The structured error body of an {@code isError=true} tool result: a stable {@code code}, an
 * agent-readable {@code message}, and optional field-level {@code details}. Never carries stack
 * traces, SQL text or internal class names — see {@code McpToolErrorTranslator}.
 */
public record McpToolError(
        McpErrorCode code, String message, @Nullable List<String> details) {}
