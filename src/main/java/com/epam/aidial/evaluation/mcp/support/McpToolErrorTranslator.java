package com.epam.aidial.evaluation.mcp.support;

import com.epam.aidial.evaluation.mcp.constants.McpErrorMessages;
import com.epam.aidial.evaluation.mcp.model.McpErrorCode;
import com.epam.aidial.evaluation.mcp.model.McpToolError;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreErrorCode;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreErrorMapper;
import com.epam.aidial.evaluation.runner.client.mcp.McpInvocationException;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.TryItOutService.TryItOutValidationException;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityRuleException;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import com.epam.aidial.evaluation.service.domain.exception.InvalidOperationException;
import com.epam.aidial.evaluation.service.domain.exception.RunNotTerminalException;
import com.epam.aidial.evaluation.service.domain.exception.TooManyRunsException;
import com.epam.aidial.evaluation.service.domain.exception.UniqueConstraintViolationException;
import com.epam.aidial.evaluation.service.domain.exception.VersionConflictException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Translates a {@link RuntimeException} raised inside a tool body into the MCP structured error
 * contract (D-F5). Branches are ordered most specific first; every branch logs with the exception
 * as the last SLF4J argument — {@code warn} for a mapped exception, {@code error} for an unmapped
 * one.
 *
 * <p><b>Known limitation (recorded, not implemented here):</b>
 * {@code service.domain.exception.PayloadTooLargeException} extends the checked {@code
 * java.io.IOException}, so it cannot be caught by {@code catch (RuntimeException e)} in
 * {@link McpToolExecutor#execute}, nor matched by a {@code case PayloadTooLargeException} pattern
 * against a {@code RuntimeException} selector (the two types are unrelated, so such a pattern does
 * not compile). It is therefore omitted from this translator. A future MCP tool that can produce
 * this condition (e.g. a CSV-import-like tool) must catch it at the call site and rethrow as an
 * unchecked exception before reaching {@link McpToolExecutor}; see design.md D-F5.
 */
@Component
@LogExecution
@RequiredArgsConstructor
@Slf4j
public class McpToolErrorTranslator {

    /** Fixed message for {@link AccessDeniedException} — never echoes the exception's own message. */
    private static final String ACCESS_DENIED_MESSAGE = "Access denied";

    /**
     * Fixed message for {@link ResourceAccessException} — never {@code getMessage()}, which
     * contains the DIAL Core URL.
     */
    private static final String DIAL_CORE_UNREACHABLE_MESSAGE = "DIAL Core is unreachable";

    private final JsonMapper objectMapper;

    public McpToolError translate(RuntimeException e) {
        return switch (e) {
            case TryItOutValidationException ex -> {
                log.warn("MCP tool validation failure (try-out): {}", ex.getMessage(), ex);
                yield new McpToolError(McpErrorCode.VALIDATION_ERROR, ex.getMessage(), tryItOutDetails(ex));
            }
            case FilterValidationException ex -> {
                log.warn("MCP tool validation failure (filter): {}", ex.getMessage(), ex);
                yield new McpToolError(McpErrorCode.VALIDATION_ERROR, ex.getMessage(), mapDetails(ex.getDetails()));
            }
            case ConstraintViolationException ex -> {
                log.warn("MCP tool validation failure (constraint): {}", ex.getMessage(), ex);
                yield new McpToolError(McpErrorCode.VALIDATION_ERROR, ex.getMessage(), constraintViolationDetails(ex));
            }
            case ValidationException ex -> {
                log.warn("MCP tool validation failure: {}", ex.getMessage(), ex);
                yield new McpToolError(McpErrorCode.VALIDATION_ERROR, ex.getMessage(), null);
            }
            case EntityNotFoundException ex -> {
                log.warn("MCP tool entity not found: {}", ex.getMessage(), ex);
                yield new McpToolError(McpErrorCode.NOT_FOUND, ex.getMessage(), null);
            }
            case UniqueConstraintViolationException ex -> {
                log.warn("MCP tool unique constraint violation: {}", ex.getMessage(), ex);
                yield new McpToolError(McpErrorCode.UNIQUE_CONSTRAINT_VIOLATION, ex.getMessage(), null);
            }
            case DatasetVisibilityRuleException ex -> {
                log.warn("MCP tool dataset visibility rule violation: {}", ex.getMessage(), ex);
                yield new McpToolError(
                        McpErrorCode.INVALID_OPERATION,
                        ex.getMessage(),
                        List.of(ex.getErrorCode().name()));
            }
            case InvalidOperationException ex -> {
                log.warn("MCP tool invalid operation: {}", ex.getMessage(), ex);
                yield new McpToolError(McpErrorCode.INVALID_OPERATION, ex.getMessage(), null);
            }
            case VersionConflictException ex -> {
                log.warn("MCP tool version conflict: {}", ex.getMessage(), ex);
                yield new McpToolError(McpErrorCode.VERSION_CONFLICT, ex.getMessage(), null);
            }
            case OptimisticLockingFailureException ex -> {
                log.warn("MCP tool optimistic locking failure: {}", ex.getMessage(), ex);
                yield new McpToolError(McpErrorCode.VERSION_CONFLICT, ex.getMessage(), null);
            }
            case TooManyRunsException ex -> {
                log.warn("MCP tool too many runs: {}", ex.getMessage(), ex);
                yield new McpToolError(McpErrorCode.TOO_MANY_REQUESTS, ex.getMessage(), null);
            }
            case RunNotTerminalException ex -> {
                log.warn("MCP tool run not terminal: {}", ex.getMessage(), ex);
                yield new McpToolError(McpErrorCode.RUN_NOT_TERMINAL, ex.getMessage(), null);
            }
            case AccessDeniedException ex -> {
                log.warn("MCP tool access denied: {}", ex.getMessage(), ex);
                yield new McpToolError(McpErrorCode.ACCESS_DENIED, ACCESS_DENIED_MESSAGE, null);
            }
            case DialCoreClientException ex -> {
                McpErrorCode code = toMcpErrorCode(DialCoreErrorMapper.toDialCoreErrorCode(ex.getStatusCode()));
                log.warn("MCP tool DIAL Core client error ({}): {}", code, ex.getMessage(), ex);
                yield new McpToolError(code, ex.getMessage(), null);
            }
            case ResourceAccessException ex -> {
                McpErrorCode code = hasTimeoutCause(ex) ? McpErrorCode.UPSTREAM_TIMEOUT : McpErrorCode.UPSTREAM_ERROR;
                log.warn("MCP tool DIAL Core unreachable ({})", code, ex);
                yield new McpToolError(code, DIAL_CORE_UNREACHABLE_MESSAGE, null);
            }
            case McpInvocationException ex -> {
                log.warn("MCP tool upstream MCP invocation error: {}", ex.getMessage(), ex);
                yield new McpToolError(McpErrorCode.UPSTREAM_ERROR, ex.getMessage(), null);
            }
            case UnsupportedFeatureException ex -> {
                log.warn("MCP tool call rejected an unsupported feature: {}", ex.getMessage(), ex);
                yield new McpToolError(McpErrorCode.NOT_SUPPORTED, ex.getMessage(), null);
            }
            default -> {
                log.error("Unhandled MCP tool exception", e);
                yield new McpToolError(McpErrorCode.INTERNAL_ERROR, McpErrorMessages.UNEXPECTED_SERVER_ERROR, null);
            }
        };
    }

    private static McpErrorCode toMcpErrorCode(DialCoreErrorCode dialCoreErrorCode) {
        return switch (dialCoreErrorCode) {
            case UPSTREAM_TIMEOUT -> McpErrorCode.UPSTREAM_TIMEOUT;
            case UPSTREAM_AUTH_ERROR, AUTHENTICATION_REQUIRED -> McpErrorCode.UPSTREAM_AUTH_ERROR;
            case ACCESS_DENIED -> McpErrorCode.ACCESS_DENIED;
            case VALIDATION_ERROR -> McpErrorCode.VALIDATION_ERROR;
            case UPSTREAM_NOT_FOUND, NOT_FOUND, UPSTREAM_ERROR -> McpErrorCode.UPSTREAM_ERROR;
        };
    }

    private static boolean hasTimeoutCause(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private @Nullable List<String> tryItOutDetails(TryItOutValidationException ex) {
        Object resolvedRequest = ex.getResolvedRequest();
        if (resolvedRequest == null) {
            return null;
        }
        try {
            return List.of(objectMapper.writeValueAsString(resolvedRequest));
        } catch (JacksonException e) {
            log.warn("Failed to serialize resolvedRequest for MCP validation error details: {}", e.getMessage(), e);
            return null;
        }
    }

    private static @Nullable List<String> mapDetails(@Nullable Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        return details.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .toList();
    }

    private static @Nullable List<String> constraintViolationDetails(ConstraintViolationException ex) {
        Set<ConstraintViolation<?>> violations = ex.getConstraintViolations();
        if (violations == null || violations.isEmpty()) {
            return null;
        }
        return violations.stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .toList();
    }
}
