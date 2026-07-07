package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.dto.KeyValueTemplateDto;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;

@DisplayName("DeploymentInvocationSupport")
class DeploymentInvocationSupportTest {

    @Nested
    @DisplayName("resolveExecutionStatus")
    class ResolveExecutionStatus {
        @Test
        @DisplayName("2xx → SUCCESS")
        void success() {
            assertThat(DeploymentInvocationSupport.resolveExecutionStatus(200)).isEqualTo(ExecutionStatus.SUCCESS);
            assertThat(DeploymentInvocationSupport.resolveExecutionStatus(204)).isEqualTo(ExecutionStatus.SUCCESS);
        }

        @Test
        @DisplayName("401/403 → ERROR")
        void authError() {
            assertThat(DeploymentInvocationSupport.resolveExecutionStatus(401)).isEqualTo(ExecutionStatus.ERROR);
            assertThat(DeploymentInvocationSupport.resolveExecutionStatus(403)).isEqualTo(ExecutionStatus.ERROR);
        }

        @Test
        @DisplayName("other non-2xx → FAILED")
        void otherFailed() {
            assertThat(DeploymentInvocationSupport.resolveExecutionStatus(400)).isEqualTo(ExecutionStatus.FAILED);
            assertThat(DeploymentInvocationSupport.resolveExecutionStatus(404)).isEqualTo(ExecutionStatus.FAILED);
            assertThat(DeploymentInvocationSupport.resolveExecutionStatus(500)).isEqualTo(ExecutionStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("isTimeoutException")
    class IsTimeoutException {
        @Test
        @DisplayName("true when a timeout is anywhere in the cause chain")
        void nestedTimeout() {
            final Exception e = new RuntimeException("wrap", new SocketTimeoutException("t"));
            assertThat(DeploymentInvocationSupport.isTimeoutException(e)).isTrue();
        }

        @Test
        @DisplayName("false for a non-timeout exception")
        void nonTimeout() {
            assertThat(DeploymentInvocationSupport.isTimeoutException(new IllegalStateException("x")))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("nextBackoffDelayMs")
    class NextBackoffDelayMs {
        @Test
        @DisplayName("first retry uses the base delay")
        void firstRetryBase() {
            assertThat(DeploymentInvocationSupport.nextBackoffDelayMs(1, 100L, 2.0, 10_000L))
                    .isEqualTo(100L);
        }

        @Test
        @DisplayName("delay grows exponentially with the attempt")
        void exponentialGrowth() {
            assertThat(DeploymentInvocationSupport.nextBackoffDelayMs(3, 100L, 2.0, 10_000L))
                    .isEqualTo(400L);
        }

        @Test
        @DisplayName("delay is capped at the maximum")
        void capped() {
            assertThat(DeploymentInvocationSupport.nextBackoffDelayMs(10, 100L, 2.0, 500L))
                    .isEqualTo(500L);
        }
    }

    @Nested
    @DisplayName("isRetryable")
    class IsRetryable {
        @Test
        @DisplayName("false once attempts are exhausted")
        void exhausted() {
            assertThat(DeploymentInvocationSupport.isRetryable(ExecutionStatus.TIMEOUT, null, 2, 2))
                    .isFalse();
        }

        @Test
        @DisplayName("TIMEOUT is retryable")
        void timeout() {
            assertThat(DeploymentInvocationSupport.isRetryable(ExecutionStatus.TIMEOUT, null, 0, 2))
                    .isTrue();
        }

        @Test
        @DisplayName("ERROR with no status code (network) is retryable")
        void networkError() {
            assertThat(DeploymentInvocationSupport.isRetryable(ExecutionStatus.ERROR, null, 0, 2))
                    .isTrue();
        }

        @Test
        @DisplayName("429 and 5xx are retryable")
        void rateLimitAndServerError() {
            assertThat(DeploymentInvocationSupport.isRetryable(ExecutionStatus.FAILED, 429, 0, 2))
                    .isTrue();
            assertThat(DeploymentInvocationSupport.isRetryable(ExecutionStatus.FAILED, 503, 0, 2))
                    .isTrue();
        }

        @Test
        @DisplayName("401 and success are not retryable")
        void notRetryable() {
            assertThat(DeploymentInvocationSupport.isRetryable(ExecutionStatus.ERROR, 401, 0, 2))
                    .isFalse();
            assertThat(DeploymentInvocationSupport.isRetryable(ExecutionStatus.SUCCESS, 200, 0, 2))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("buildQueryParams")
    class BuildQueryParams {
        @Test
        @DisplayName("builds a multi-value map from key/value pairs")
        void builds() {
            final MultiValueMap<String, String> params = DeploymentInvocationSupport.buildQueryParams(List.of(
                    KeyValueTemplateDto.builder().key("a").value("1").build(),
                    KeyValueTemplateDto.builder().key("b").value("2").build()));

            assertThat(params.getFirst("a")).isEqualTo("1");
            assertThat(params.getFirst("b")).isEqualTo("2");
        }

        @Test
        @DisplayName("skips pairs with a null key or value")
        void skipsNulls() {
            final MultiValueMap<String, String> params = DeploymentInvocationSupport.buildQueryParams(List.of(
                    KeyValueTemplateDto.builder().key("a").value(null).build(),
                    KeyValueTemplateDto.builder().key(null).value("2").build(),
                    KeyValueTemplateDto.builder().key("c").value("3").build()));

            assertThat(params).containsOnlyKeys("c");
        }

        @Test
        @DisplayName("returns an empty map for null input")
        void nullInput() {
            assertThat(DeploymentInvocationSupport.buildQueryParams(null)).isEmpty();
        }
    }
}
