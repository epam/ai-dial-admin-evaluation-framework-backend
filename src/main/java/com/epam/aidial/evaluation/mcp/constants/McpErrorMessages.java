package com.epam.aidial.evaluation.mcp.constants;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Fixed, non-leaking messages the MCP error contract emits when no specific message is safe. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class McpErrorMessages {

    /** Generic message for {@code INTERNAL_ERROR}; never carries the underlying exception text. */
    public static final String UNEXPECTED_SERVER_ERROR = "Unexpected server error";
}
