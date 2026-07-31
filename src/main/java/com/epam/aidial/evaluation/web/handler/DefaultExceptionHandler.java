package com.epam.aidial.evaluation.web.handler;

import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreErrorCode;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreErrorMapper;
import com.epam.aidial.evaluation.runner.client.mcp.McpInvocationException;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.TryItOutService.TryItOutValidationException;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityErrorCode;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityRuleException;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import com.epam.aidial.evaluation.service.domain.exception.InvalidOperationException;
import com.epam.aidial.evaluation.service.domain.exception.PayloadTooLargeException;
import com.epam.aidial.evaluation.service.domain.exception.RunNotTerminalException;
import com.epam.aidial.evaluation.service.domain.exception.SnapshotDatasetMissingException;
import com.epam.aidial.evaluation.service.domain.exception.SnapshotSuiteMissingException;
import com.epam.aidial.evaluation.service.domain.exception.TooManyRunsException;
import com.epam.aidial.evaluation.service.domain.exception.UniqueConstraintViolationException;
import com.epam.aidial.evaluation.service.domain.exception.UnsupportedSnapshotVersionException;
import com.epam.aidial.evaluation.service.domain.exception.VersionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Slf4j
@LogExecution
public class DefaultExceptionHandler {

    @ExceptionHandler(DialCoreClientException.class)
    public ResponseEntity<ErrorView> handleDialCoreClientException(HttpServletRequest req, DialCoreClientException ex) {
        logUncaught(ex);
        HttpStatus status = DialCoreErrorMapper.toHttpStatus(ex.getStatusCode());
        ErrorCode code = toErrorCode(DialCoreErrorMapper.toDialCoreErrorCode(ex.getStatusCode()));
        String message = ex.getMessage() != null ? ex.getMessage() : status.getReasonPhrase();
        return ResponseEntity.status(status).body(new ErrorView(req, status, code, message));
    }

    @ExceptionHandler(McpInvocationException.class)
    public ResponseEntity<ErrorView> handleMcpInvocationException(HttpServletRequest req, McpInvocationException ex) {
        logUncaught(ex);
        int statusCode = ex.getStatusCode();
        HttpStatus status = HttpStatus.resolve(statusCode);
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        ErrorCode code = mapMcpStatusToErrorCode(statusCode);
        String message = ex.getMessage() != null ? ex.getMessage() : status.getReasonPhrase();
        return ResponseEntity.status(status).body(new ErrorView(req, status, code, message));
    }

    private static ErrorCode mapMcpStatusToErrorCode(int statusCode) {
        if (statusCode == HttpStatus.GATEWAY_TIMEOUT.value()) {
            return ErrorCode.UPSTREAM_TIMEOUT;
        }
        return ErrorCode.UPSTREAM_ERROR;
    }

    private static ErrorCode toErrorCode(DialCoreErrorCode dialCoreErrorCode) {
        return switch (dialCoreErrorCode) {
            case AUTHENTICATION_REQUIRED -> ErrorCode.AUTHENTICATION_REQUIRED;
            case ACCESS_DENIED -> ErrorCode.ACCESS_DENIED;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case VALIDATION_ERROR -> ErrorCode.VALIDATION_ERROR;
            case UPSTREAM_AUTH_ERROR -> ErrorCode.UPSTREAM_AUTH_ERROR;
            case UPSTREAM_NOT_FOUND -> ErrorCode.UPSTREAM_NOT_FOUND;
            case UPSTREAM_ERROR -> ErrorCode.UPSTREAM_ERROR;
            case UPSTREAM_TIMEOUT -> ErrorCode.UPSTREAM_TIMEOUT;
        };
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ExceptionHandler({EntityNotFoundException.class, NoResourceFoundException.class})
    public ErrorView handleEntityNotFoundError(HttpServletRequest req, Exception ex) {
        logUncaught(ex);
        return new ErrorView(req, HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, ex.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(FilterValidationException.class)
    public ErrorView handleFilterValidationError(HttpServletRequest req, FilterValidationException ex) {
        logUncaught(ex);
        return new ErrorView(req, HttpStatus.BAD_REQUEST, ErrorCode.INVALID_FILTER, ex.getMessage(), ex.getDetails());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(TryItOutValidationException.class)
    public ErrorView handleTryItOutValidationError(HttpServletRequest req, TryItOutValidationException ex) {
        logUncaught(ex);
        return new ErrorView(
                req,
                HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_ERROR,
                ex.getMessage(),
                Map.of("resolvedRequest", ex.getResolvedRequest()));
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(ValidationException.class)
    public ErrorView handleValidationError(HttpServletRequest req, ValidationException ex) {
        logUncaught(ex);
        return new ErrorView(req, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage());
    }

    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    @ExceptionHandler({HttpRequestMethodNotSupportedException.class})
    public ErrorView handleMethodNotAllowedError(HttpServletRequest req, Exception ex) {
        logUncaught(ex);
        return new ErrorView(req, HttpStatus.METHOD_NOT_ALLOWED, null, ex.getMessage());
    }

    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(AccessDeniedException.class)
    public ErrorView handleAuthorizationException(HttpServletRequest req, Exception ex) {
        logUncaught(ex);
        return new ErrorView(req, HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED, ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorView> handleWrongJsonError(HttpServletRequest req, HttpMessageNotReadableException ex) {
        logUncaught(ex);
        if (findCause(ex, PayloadTooLargeException.class) != null) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .body(new ErrorView(
                            req,
                            HttpStatus.CONTENT_TOO_LARGE,
                            ErrorCode.PAYLOAD_TOO_LARGE,
                            findCause(ex, PayloadTooLargeException.class).getMessage()));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorView(req, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage()));
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MissingRequestValueException.class)
    public ErrorView handleMissingRequestValueError(HttpServletRequest req, Exception ex) {
        logUncaught(ex);
        return new ErrorView(req, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage());
    }

    /**
     * Thrown when Spring cannot convert a path/query/header parameter to the controller method's
     * declared type — e.g. a malformed UUID, a non-numeric int, or an unknown enum value. Spring
     * defaults to a 500 for this; we surface it as a client-side validation error instead.
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ErrorView handleMethodArgumentTypeMismatchError(
            HttpServletRequest req, MethodArgumentTypeMismatchException ex) {
        logUncaught(ex);
        String paramName = ex.getName();
        Class<?> requiredType = ex.getRequiredType();
        String requiredTypeName = requiredType != null ? requiredType.getSimpleName() : "expected type";
        Object value = ex.getValue();
        String message =
                String.format("Parameter '%s' must be a valid %s (received: '%s')", paramName, requiredTypeName, value);
        return new ErrorView(req, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(ConstraintViolationException.class)
    public ErrorView handleConstraintViolationError(HttpServletRequest req, Exception ex) {
        logUncaught(ex);
        return new ErrorView(req, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage());
    }

    /**
     * Handles method-level validation exceptions (Spring Boot 3+).
     * Thrown when @Validated is on controller class and @Size/@Pattern etc. on @RequestParam.
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ErrorView handleHandlerMethodValidationError(HttpServletRequest req, HandlerMethodValidationException ex) {
        logUncaught(ex);
        StringBuilder message = new StringBuilder();
        ex.getParameterValidationResults().forEach(result -> {
            String paramName = result.getMethodParameter().getParameterName();
            result.getResolvableErrors()
                    .forEach(error -> message.append("Parameter [")
                            .append(paramName)
                            .append("]: ")
                            .append(error.getDefaultMessage())
                            .append(". "));
        });
        String errorMessage = !message.isEmpty() ? message.toString().trim() : ex.getMessage();
        return new ErrorView(req, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, errorMessage);
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(VersionConflictException.class)
    public ErrorView handleVersionConflictError(HttpServletRequest req, VersionConflictException ex) {
        logUncaught(ex);
        return new ErrorView(req, HttpStatus.CONFLICT, ErrorCode.VERSION_CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(TooManyRunsException.class)
    public ResponseEntity<ErrorView> handleTooManyRunsError(HttpServletRequest req, TooManyRunsException ex) {
        logUncaught(ex);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorView(
                        req,
                        HttpStatus.TOO_MANY_REQUESTS,
                        ErrorCode.TOO_MANY_REQUESTS,
                        ex.getMessage(),
                        ex.getDetails()));
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(InvalidOperationException.class)
    public ErrorView handleInvalidOperationError(HttpServletRequest req, InvalidOperationException ex) {
        logUncaught(ex);
        return new ErrorView(req, HttpStatus.CONFLICT, ErrorCode.INVALID_OPERATION, ex.getMessage());
    }

    @ExceptionHandler(DatasetVisibilityRuleException.class)
    public ResponseEntity<ErrorView> handleDatasetVisibilityRuleError(
            HttpServletRequest req, DatasetVisibilityRuleException ex) {
        logUncaught(ex);
        ErrorCode wireCode = toWireErrorCode(ex.getErrorCode());
        HttpStatus status = visibilityStatusFor(ex.getErrorCode());
        return ResponseEntity.status(status).body(new ErrorView(req, status, wireCode, ex.getMessage()));
    }

    /**
     * Maps the {@code tg_test_suites_private_binding_guard} trigger's
     * {@code RAISE EXCEPTION USING ERRCODE='P0001'} to HTTP 409 with
     * {@link ErrorCode#PRIVATE_DATASET_ALREADY_BOUND}. The standard
     * {@code 23505 → UNIQUE_CONSTRAINT_VIOLATION} mapping is untouched — only the bespoke
     * P0001 from this one trigger is intercepted here. Any other DataAccessException is
     * rethrown so the existing handlers (and the default fallback) see it.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorView> handleDataAccessException(HttpServletRequest req, DataAccessException ex)
            throws DataAccessException {
        java.sql.SQLException sqlEx = findSqlException(ex);
        if (sqlEx != null && "P0001".equals(sqlEx.getSQLState())) {
            logUncaught(ex);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ErrorView(
                            req,
                            HttpStatus.CONFLICT,
                            ErrorCode.PRIVATE_DATASET_ALREADY_BOUND,
                            sqlEx.getMessage() != null ? sqlEx.getMessage() : "PRIVATE_DATASET_ALREADY_BOUND"));
        }
        throw ex;
    }

    private static java.sql.SQLException findSqlException(Throwable ex) {
        Throwable t = ex;
        int depth = 0;
        while (t != null && depth < 8) {
            if (t instanceof java.sql.SQLException sql) {
                return sql;
            }
            t = t.getCause();
            depth++;
        }
        return null;
    }

    /**
     * HTTP status mapping for {@link DatasetVisibilityRuleException}. Create-side field
     * combination errors are HTTP 400 (client supplied an invalid combination); cross-row
     * business rules are HTTP 409 (server-side invariant violation).
     */
    private static HttpStatus visibilityStatusFor(DatasetVisibilityErrorCode code) {
        return switch (code) {
            case PRIVATE_DATASET_REQUIRES_SUITE_BINDING, PUBLIC_DATASET_FORBIDS_SUITE_BINDING, VALIDATION_ERROR ->
                HttpStatus.BAD_REQUEST;
            case PRIVATE_DATASET_REBIND_FORBIDDEN, PRIVATE_TRANSITION_INVALID_BINDING_COUNT, SUITE_HAS_NO_DATASET ->
                HttpStatus.CONFLICT;
        };
    }

    private static ErrorCode toWireErrorCode(DatasetVisibilityErrorCode code) {
        return switch (code) {
            case PRIVATE_DATASET_REQUIRES_SUITE_BINDING -> ErrorCode.PRIVATE_DATASET_REQUIRES_SUITE_BINDING;
            case PUBLIC_DATASET_FORBIDS_SUITE_BINDING -> ErrorCode.PUBLIC_DATASET_FORBIDS_SUITE_BINDING;
            case VALIDATION_ERROR -> ErrorCode.VALIDATION_ERROR;
            case PRIVATE_DATASET_REBIND_FORBIDDEN -> ErrorCode.PRIVATE_DATASET_REBIND_FORBIDDEN;
            case PRIVATE_TRANSITION_INVALID_BINDING_COUNT -> ErrorCode.PRIVATE_TRANSITION_INVALID_BINDING_COUNT;
            case SUITE_HAS_NO_DATASET -> ErrorCode.SUITE_HAS_NO_DATASET;
        };
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(SnapshotSuiteMissingException.class)
    public ErrorView handleSnapshotSuiteMissingError(HttpServletRequest req, SnapshotSuiteMissingException ex) {
        logUncaught(ex);
        return new ErrorView(req, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.SNAPSHOT_SUITE_MISSING, ex.getMessage());
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(SnapshotDatasetMissingException.class)
    public ErrorView handleSnapshotDatasetMissingError(HttpServletRequest req, SnapshotDatasetMissingException ex) {
        logUncaught(ex);
        return new ErrorView(
                req, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.SNAPSHOT_DATASET_MISSING, ex.getMessage());
    }

    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    @ExceptionHandler(UnsupportedSnapshotVersionException.class)
    public ErrorView handleUnsupportedSnapshotVersionError(
            HttpServletRequest req, UnsupportedSnapshotVersionException ex) {
        logUncaught(ex);
        return new ErrorView(
                req, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.UNSUPPORTED_SNAPSHOT_VERSION, ex.getMessage());
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(RunNotTerminalException.class)
    public ErrorView handleRunNotTerminalError(HttpServletRequest req, RunNotTerminalException ex) {
        logUncaught(ex);
        return new ErrorView(req, HttpStatus.CONFLICT, ErrorCode.RUN_NOT_TERMINAL, ex.getMessage());
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(UniqueConstraintViolationException.class)
    public ErrorView handleUniqueConstraintViolationError(
            HttpServletRequest req, UniqueConstraintViolationException ex) {
        logUncaught(ex);
        return new ErrorView(req, HttpStatus.CONFLICT, ErrorCode.UNIQUE_CONSTRAINT_VIOLATION, ex.getMessage());
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public ErrorView handleGeneralError(HttpServletRequest req, Exception ex) {
        log.warn("[{}] Request: {} raised exception", req.getMethod(), req.getServletPath(), ex);
        return new ErrorView(req, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, ex.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ErrorView handleValidationExceptions(HttpServletRequest req, MethodArgumentNotValidException ex) {
        logUncaught(ex);
        StringBuilder message = new StringBuilder();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            if (error instanceof FieldError fieldError) {
                message.append("Field [").append(fieldError.getField()).append("]: ");
            }
            message.append(error.getDefaultMessage()).append("\n");
        });
        return new ErrorView(req, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message.toString());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    public ErrorView handleIllegalArgumentError(HttpServletRequest req, Exception ex) {
        logUncaught(ex);
        return new ErrorView(req, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, ex.getMessage());
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<String> handleAsyncRequestNotUsableException(
            HttpServletRequest req, AsyncRequestNotUsableException ex) {
        logUncaught(ex);
        log.info(
                "[{}] Request: {} raised AsyncRequestNotUsableException. " + "Streaming has been closed by the client",
                req.getMethod(),
                req.getServletPath());
        return ResponseEntity.ok("Streaming has been closed by the client.");
    }

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<String> handleAsyncRequestTimeoutException(
            HttpServletRequest req, AsyncRequestTimeoutException ex) {
        logUncaught(ex);
        log.warn(
                "[{}] Request: {} raised AsyncRequestTimeoutException. "
                        + "Streaming has timed out and should be re-initiated by the client",
                req.getMethod(),
                req.getServletPath());
        return ResponseEntity.ok("Streaming has timed out and should be re-initiated by the client.");
    }

    private static <T extends Throwable> T findCause(Throwable ex, Class<T> causeType) {
        Throwable current = ex;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return causeType.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    protected void logUncaught(final Exception ex) {
        if (!log.isDebugEnabled()) {
            return;
        }

        log.debug("Uncaught exception.", ex);
    }
}
