package com.epam.aidial.evaluation.mcp.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.mcp.model.McpErrorCode;
import com.epam.aidial.evaluation.mcp.model.McpToolError;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreErrorCode;
import com.epam.aidial.evaluation.runner.client.mcp.McpInvocationException;
import com.epam.aidial.evaluation.runner.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.service.domain.TryItOutService.TryItOutValidationException;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityErrorCode;
import com.epam.aidial.evaluation.service.domain.exception.DatasetVisibilityRuleException;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import com.epam.aidial.evaluation.service.domain.exception.InvalidOperationException;
import com.epam.aidial.evaluation.service.domain.exception.RunNotTerminalException;
import com.epam.aidial.evaluation.service.domain.exception.TooManyRunsException;
import com.epam.aidial.evaluation.service.domain.exception.UniqueConstraintViolationException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.exception.VersionConflictException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("McpToolErrorTranslator")
class McpToolErrorTranslatorTest {

    private final McpToolErrorTranslator translator =
            new McpToolErrorTranslator(JsonMapper.builder().build());

    @Test
    @DisplayName("TryItOutValidationException -> VALIDATION_ERROR with resolvedRequest in details when present")
    void tryItOutValidationExceptionWithResolvedRequestIncludesItInDetails() {
        ResolvedRequestDto resolvedRequest =
                ResolvedRequestDto.builder().url("https://example.com").build();
        TryItOutValidationException ex = new TryItOutValidationException("bad model body", resolvedRequest);

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.VALIDATION_ERROR);
        assertThat(toolError.message()).isEqualTo("bad model body");
        assertThat(toolError.details()).hasSize(1);
        assertThat(toolError.details().get(0)).contains("https://example.com");
    }

    @Test
    @DisplayName("TryItOutValidationException -> VALIDATION_ERROR with no details when resolvedRequest is absent")
    void tryItOutValidationExceptionWithoutResolvedRequestHasNoDetails() {
        TryItOutValidationException ex = new TryItOutValidationException("bad model body", null);

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.VALIDATION_ERROR);
        assertThat(toolError.details()).isNull();
    }

    @Test
    @DisplayName("FilterValidationException -> VALIDATION_ERROR (deliberate: MCP does not carry INVALID_FILTER)")
    void filterValidationExceptionMapsToValidationErrorWithDetails() {
        FilterValidationException ex = new FilterValidationException("bad filter", Map.of("field", "status"));

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.VALIDATION_ERROR);
        assertThat(toolError.message()).isEqualTo("bad filter");
        assertThat(toolError.details()).containsExactly("field: status");
    }

    @Test
    @DisplayName("service.domain.exception.ValidationException -> VALIDATION_ERROR")
    void plainValidationExceptionMapsToValidationError() {
        ValidationException ex = new ValidationException("invalid input");

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.VALIDATION_ERROR);
        assertThat(toolError.message()).isEqualTo("invalid input");
    }

    @Test
    @DisplayName("runner ValidationException -> VALIDATION_ERROR")
    void runnerValidationExceptionMapsToValidationError() {
        RuntimeException ex = RunnerValidationExceptionFixture.runnerValidationException("invalid input");

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.VALIDATION_ERROR);
        assertThat(toolError.message()).isEqualTo("invalid input");
    }

    @Test
    @DisplayName("jakarta ConstraintViolationException -> VALIDATION_ERROR with violations in details")
    void constraintViolationExceptionMapsToValidationErrorWithViolationDetails() {
        Path path = mock(Path.class);
        when(path.toString()).thenReturn("field1");
        ConstraintViolation<?> violation = mock(ConstraintViolation.class);
        when(violation.getPropertyPath()).thenReturn(path);
        when(violation.getMessage()).thenReturn("must not be blank");
        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.VALIDATION_ERROR);
        assertThat(toolError.details()).containsExactly("field1: must not be blank");
    }

    @Test
    @DisplayName("EntityNotFoundException -> NOT_FOUND")
    void entityNotFoundExceptionMapsToNotFound() {
        EntityNotFoundException ex = new EntityNotFoundException("Deployment not found: abc");

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.NOT_FOUND);
        assertThat(toolError.message()).isEqualTo("Deployment not found: abc");
    }

    @Test
    @DisplayName("UniqueConstraintViolationException -> UNIQUE_CONSTRAINT_VIOLATION")
    void uniqueConstraintViolationExceptionMapsToUniqueConstraintViolation() {
        UniqueConstraintViolationException ex = new UniqueConstraintViolationException("duplicate name", "suite-1");

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.UNIQUE_CONSTRAINT_VIOLATION);
        assertThat(toolError.message()).isEqualTo("duplicate name");
    }

    @Test
    @DisplayName("DatasetVisibilityRuleException -> INVALID_OPERATION with the rule code in details")
    void datasetVisibilityRuleExceptionMapsToInvalidOperationWithRuleCodeInDetails() {
        DatasetVisibilityRuleException ex =
                new DatasetVisibilityRuleException(DatasetVisibilityErrorCode.SUITE_HAS_NO_DATASET, "no dataset");

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.INVALID_OPERATION);
        assertThat(toolError.details()).containsExactly("SUITE_HAS_NO_DATASET");
    }

    @Test
    @DisplayName("InvalidOperationException -> INVALID_OPERATION")
    void invalidOperationExceptionMapsToInvalidOperation() {
        InvalidOperationException ex = new InvalidOperationException("cannot rebind");

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.INVALID_OPERATION);
        assertThat(toolError.message()).isEqualTo("cannot rebind");
    }

    @Test
    @DisplayName("VersionConflictException -> VERSION_CONFLICT")
    void versionConflictExceptionMapsToVersionConflict() {
        VersionConflictException ex = new VersionConflictException("stale version", "suite-1", 3L);

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.VERSION_CONFLICT);
        assertThat(toolError.message()).isEqualTo("stale version");
    }

    @Test
    @DisplayName("Spring OptimisticLockingFailureException -> VERSION_CONFLICT")
    void optimisticLockingFailureExceptionMapsToVersionConflict() {
        OptimisticLockingFailureException ex = new OptimisticLockingFailureException("stale row");

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.VERSION_CONFLICT);
        assertThat(toolError.message()).isEqualTo("stale row");
    }

    @Test
    @DisplayName("TooManyRunsException -> TOO_MANY_REQUESTS")
    void tooManyRunsExceptionMapsToTooManyRequests() {
        TooManyRunsException ex = new TooManyRunsException("too many concurrent runs", Map.of("limit", 5));

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.TOO_MANY_REQUESTS);
        assertThat(toolError.message()).isEqualTo("too many concurrent runs");
    }

    @Test
    @DisplayName("RunNotTerminalException -> RUN_NOT_TERMINAL")
    void runNotTerminalExceptionMapsToRunNotTerminal() {
        RunNotTerminalException ex = new RunNotTerminalException("run is RUNNING");

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.RUN_NOT_TERMINAL);
        assertThat(toolError.message()).isEqualTo("run is RUNNING");
    }

    @Test
    @DisplayName(
            "Spring AccessDeniedException -> ACCESS_DENIED with a fixed message, never the exception's own message")
    void accessDeniedExceptionMapsToAccessDeniedWithFixedMessage() {
        AccessDeniedException ex = new AccessDeniedException("internal role check failed for user X");

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.ACCESS_DENIED);
        assertThat(toolError.message()).isEqualTo("Access denied");
        assertThat(toolError.message()).doesNotContain("user X");
    }

    @ParameterizedTest
    @EnumSource(DialCoreErrorCode.class)
    @DisplayName("DialCoreClientException -> mapped McpErrorCode for every DialCoreErrorCode value")
    void dialCoreClientExceptionMapsEveryDialCoreErrorCode(DialCoreErrorCode dialCoreErrorCode) {
        HttpStatus status =
                switch (dialCoreErrorCode) {
                    case AUTHENTICATION_REQUIRED, UPSTREAM_AUTH_ERROR -> HttpStatus.UNAUTHORIZED;
                    case ACCESS_DENIED -> HttpStatus.FORBIDDEN;
                    case NOT_FOUND, UPSTREAM_NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
                    case UPSTREAM_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
                    case UPSTREAM_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
                };
        McpErrorCode expected =
                switch (dialCoreErrorCode) {
                    case UPSTREAM_TIMEOUT -> McpErrorCode.UPSTREAM_TIMEOUT;
                    case UPSTREAM_AUTH_ERROR, AUTHENTICATION_REQUIRED -> McpErrorCode.UPSTREAM_AUTH_ERROR;
                    case ACCESS_DENIED -> McpErrorCode.ACCESS_DENIED;
                    case VALIDATION_ERROR -> McpErrorCode.VALIDATION_ERROR;
                    case UPSTREAM_NOT_FOUND, NOT_FOUND, UPSTREAM_ERROR -> McpErrorCode.UPSTREAM_ERROR;
                };
        DialCoreClientException ex = new DialCoreClientException(status, "upstream said: " + dialCoreErrorCode);

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(expected);
        assertThat(toolError.message()).isEqualTo("upstream said: " + dialCoreErrorCode);
    }

    @Test
    @DisplayName("ResourceAccessException with a socket-timeout cause -> UPSTREAM_TIMEOUT, message never the URL")
    void resourceAccessExceptionWithSocketTimeoutCauseMapsToUpstreamTimeout() {
        ResourceAccessException ex = new ResourceAccessException(
                "I/O error on GET request for \"http://core.internal/v1/deployments\": timed out",
                new SocketTimeoutException("timed out"));

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.UPSTREAM_TIMEOUT);
        assertThat(toolError.message()).isEqualTo("DIAL Core is unreachable");
        assertThat(toolError.message()).doesNotContain("core.internal");
    }

    @Test
    @DisplayName("ResourceAccessException without a timeout cause -> UPSTREAM_ERROR, message never the URL")
    void resourceAccessExceptionWithoutTimeoutCauseMapsToUpstreamError() {
        ResourceAccessException ex = new ResourceAccessException(
                "I/O error on GET request for \"http://core.internal/v1/deployments\": refused",
                new ConnectException("Connection refused"));

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.UPSTREAM_ERROR);
        assertThat(toolError.message()).isEqualTo("DIAL Core is unreachable");
        assertThat(toolError.message()).doesNotContain("core.internal");
    }

    @Test
    @DisplayName("McpInvocationException (runner-core) -> UPSTREAM_ERROR")
    void mcpInvocationExceptionMapsToUpstreamError() {
        McpInvocationException ex = new McpInvocationException(502, "TOOL_ERROR", "tool invocation failed");

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.UPSTREAM_ERROR);
        assertThat(toolError.message()).isEqualTo("tool invocation failed");
    }

    @Test
    @DisplayName("UnsupportedFeatureException -> NOT_SUPPORTED")
    void unsupportedFeatureExceptionMapsToNotSupported() {
        UnsupportedFeatureException ex = new UnsupportedFeatureException("additionalRequests");

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.NOT_SUPPORTED);
        assertThat(toolError.message()).isEqualTo(ex.getMessage());
    }

    @Test
    @DisplayName("An exception with no specific mapping -> INTERNAL_ERROR with a fixed generic message")
    void unmappedExceptionMapsToInternalErrorWithFixedGenericMessage() {
        IllegalStateException ex = new IllegalStateException("connection pool leaked a secret credential");

        McpToolError toolError = translator.translate(ex);

        assertThat(toolError.code()).isEqualTo(McpErrorCode.INTERNAL_ERROR);
        assertThat(toolError.message()).isEqualTo("Unexpected server error");
        assertThat(toolError.message()).doesNotContain("secret credential");
    }
}
