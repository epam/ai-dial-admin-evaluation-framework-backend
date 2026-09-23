package com.epam.aidial.evaluation.mcp.support;

import com.epam.aidial.evaluation.mcp.constants.McpErrorMessages;
import com.epam.aidial.evaluation.mcp.model.McpErrorCode;
import com.epam.aidial.evaluation.mcp.model.McpToolError;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The single seam every {@code @McpTool} method runs through (D-F2). Runs the tool body, encodes
 * a successful result via {@link McpToolResults#success(Object)}, and translates any
 * {@link RuntimeException} via {@link McpToolErrorTranslator} into an error result. Tool methods
 * built on this class never throw and never touch {@link CallToolResult} directly. The guarantee
 * also covers a translator failure: if {@link McpToolErrorTranslator#translate} itself throws a
 * {@link RuntimeException} (e.g. a mapper bug on an unexpected exception subtype), that failure is
 * logged and swallowed, and a generic {@code INTERNAL_ERROR} result is returned instead of letting
 * the exception escape to Spring AI's own plain-text error handling.
 */
@Component
@LogExecution
@RequiredArgsConstructor
@Slf4j
public class McpToolExecutor {

    private final McpCallerContext callerContext;
    private final McpToolErrorTranslator translator;
    private final McpToolResults results;

    /**
     * Runs a tool body that does not need the caller's identity.
     */
    public <T> CallToolResult execute(Supplier<T> body) {
        try {
            return results.success(body.get());
        } catch (RuntimeException e) {
            return results.error(translateSafely(e));
        }
    }

    /**
     * Runs a tool body that needs the caller's identity (e.g. for {@code createdBy} attribution).
     * {@link McpCallerContext} reads from the request thread the tool body executes on.
     */
    public <T> CallToolResult execute(Function<McpCallerContext, T> body) {
        try {
            return results.success(body.apply(callerContext));
        } catch (RuntimeException e) {
            return results.error(translateSafely(e));
        }
    }

    /**
     * Translates a tool body's exception, guarding against the translator itself throwing. On a
     * translator failure, logs it and falls back to a generic {@code INTERNAL_ERROR}, never
     * leaking the translator failure's own message.
     */
    private McpToolError translateSafely(RuntimeException e) {
        try {
            return translator.translate(e);
        } catch (RuntimeException translationFailure) {
            log.error(
                    "Failed to translate tool exception {}: {}",
                    e.getClass().getName(),
                    translationFailure.getMessage(),
                    translationFailure);
            return new McpToolError(McpErrorCode.INTERNAL_ERROR, McpErrorMessages.UNEXPECTED_SERVER_ERROR, null);
        }
    }
}
