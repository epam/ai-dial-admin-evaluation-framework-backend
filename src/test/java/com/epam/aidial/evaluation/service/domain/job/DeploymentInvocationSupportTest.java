package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.dto.KeyValueTemplateDto;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;

@DisplayName("DeploymentInvocationSupport")
class DeploymentInvocationSupportTest {

    @Test
    @DisplayName("resolveExecutionStatus maps 2xx→SUCCESS, 401/403→ERROR, else→FAILED")
    void resolveExecutionStatus() {
        assertThat(DeploymentInvocationSupport.resolveExecutionStatus(200)).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(DeploymentInvocationSupport.resolveExecutionStatus(299)).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(DeploymentInvocationSupport.resolveExecutionStatus(401)).isEqualTo(ExecutionStatus.ERROR);
        assertThat(DeploymentInvocationSupport.resolveExecutionStatus(403)).isEqualTo(ExecutionStatus.ERROR);
        assertThat(DeploymentInvocationSupport.resolveExecutionStatus(404)).isEqualTo(ExecutionStatus.FAILED);
        assertThat(DeploymentInvocationSupport.resolveExecutionStatus(500)).isEqualTo(ExecutionStatus.FAILED);
    }

    @Test
    @DisplayName("isTimeoutException walks the cause chain")
    void isTimeoutException() {
        assertThat(DeploymentInvocationSupport.isTimeoutException(new SocketTimeoutException("read timed out")))
                .isTrue();
        assertThat(DeploymentInvocationSupport.isTimeoutException(
                        new RuntimeException(new SocketTimeoutException("x"))))
                .isTrue();
        assertThat(DeploymentInvocationSupport.isTimeoutException(new IllegalStateException("boom")))
                .isFalse();
    }

    @Test
    @DisplayName("nextBackoffDelayMs grows exponentially and is capped")
    void nextBackoffDelayMs() {
        assertThat(DeploymentInvocationSupport.nextBackoffDelayMs(1, 100, 2.0, 10_000))
                .isEqualTo(100);
        assertThat(DeploymentInvocationSupport.nextBackoffDelayMs(2, 100, 2.0, 10_000))
                .isEqualTo(200);
        assertThat(DeploymentInvocationSupport.nextBackoffDelayMs(3, 100, 2.0, 10_000))
                .isEqualTo(400);
        assertThat(DeploymentInvocationSupport.nextBackoffDelayMs(10, 100, 2.0, 1_000))
                .isEqualTo(1_000);
    }

    @Test
    @DisplayName(
            "isRetryable: TIMEOUT, network error, 429, 5xx retry while attempts remain; 401/403 and success do not")
    void isRetryable() {
        assertThat(DeploymentInvocationSupport.isRetryable(ExecutionStatus.TIMEOUT, null, 0, 3))
                .isTrue();
        assertThat(DeploymentInvocationSupport.isRetryable(ExecutionStatus.ERROR, null, 0, 3))
                .isTrue();
        assertThat(DeploymentInvocationSupport.isRetryable(ExecutionStatus.FAILED, 429, 0, 3))
                .isTrue();
        assertThat(DeploymentInvocationSupport.isRetryable(ExecutionStatus.FAILED, 503, 0, 3))
                .isTrue();
        assertThat(DeploymentInvocationSupport.isRetryable(ExecutionStatus.ERROR, 401, 0, 3))
                .isFalse();
        assertThat(DeploymentInvocationSupport.isRetryable(ExecutionStatus.SUCCESS, 200, 0, 3))
                .isFalse();
        assertThat(DeploymentInvocationSupport.isRetryable(ExecutionStatus.TIMEOUT, null, 3, 3))
                .as("no retries once attempts are exhausted")
                .isFalse();
    }

    @Test
    @DisplayName("truncateUtf8 caps oversize strings on a byte boundary and passes short ones through")
    void truncateUtf8() {
        assertThat(DeploymentInvocationSupport.truncateUtf8(null, 10)).isNull();
        assertThat(DeploymentInvocationSupport.truncateUtf8("abc", 10)).isEqualTo("abc");
        assertThat(DeploymentInvocationSupport.truncateUtf8("abcdef", 3)).isEqualTo("abc");
        assertThat(DeploymentInvocationSupport.truncateUtf8("abcdef", 3).getBytes(StandardCharsets.UTF_8))
                .hasSizeLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("buildQueryParams skips null keys/values")
    void buildQueryParams() {
        MultiValueMap<String, String> params = DeploymentInvocationSupport.buildQueryParams(List.of(
                new KeyValueTemplateDto("a", "1"),
                new KeyValueTemplateDto(null, "2"),
                new KeyValueTemplateDto("c", null)));
        assertThat(params.getFirst("a")).isEqualTo("1");
        assertThat(params).doesNotContainKey("c");
        assertThat(params.size()).isEqualTo(1);
    }
}
