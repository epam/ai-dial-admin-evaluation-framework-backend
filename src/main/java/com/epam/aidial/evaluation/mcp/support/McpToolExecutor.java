package com.epam.aidial.evaluation.mcp.support;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The single seam every {@code @McpTool} method runs through (D-F2). Runs the tool body, encodes
 * a successful result via {@link McpToolResults#success(Object)}, and translates any
 * {@link RuntimeException} via {@link McpToolErrorTranslator} into an error result. Tool methods
 * built on this class never throw and never touch {@link CallToolResult} directly.
 */
@Component
@LogExecution
@RequiredArgsConstructor
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
            return results.error(translator.translate(e));
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
            return results.error(translator.translate(e));
        }
    }
}
