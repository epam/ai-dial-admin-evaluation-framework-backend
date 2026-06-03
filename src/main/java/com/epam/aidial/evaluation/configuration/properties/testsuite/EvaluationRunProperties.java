package com.epam.aidial.evaluation.configuration.properties.testsuite;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "test-suite-run")
public class EvaluationRunProperties {

    @NotNull
    @Valid
    private Execution execution;

    @NotNull
    @Valid
    private Retry retry;

    @NotNull
    @Valid
    private RunInputs runInputs;

    public Duration getInputsRetentionDuration() {
        return Duration.ofDays(runInputs.getRetentionDays());
    }

    @Getter
    @Setter
    public static class Execution {

        @NotNull
        @Min(1)
        private Integer defaultConcurrencyLevel;

        @NotNull
        @Min(1)
        private Integer maxConcurrencyLevel;

        @NotNull
        @Min(1000)
        private Long defaultRequestTimeoutMs;

        @NotNull
        @Min(1000)
        private Long maxRequestTimeoutMs;

        private Double defaultRateLimitRps;

        @NotNull
        @Min(1)
        private Integer resultBatchSize;

        @NotNull
        @Min(1)
        private Long maxResponseSizeBytes;

        @NotNull
        @Min(1000)
        private Long cancellationGracePeriodMs;

        @NotNull
        private List<String> headerBlacklist;
    }

    @Getter
    @Setter
    public static class Retry {

        @NotNull
        @Min(0)
        private Integer defaultMaxRetries;

        @NotNull
        @Min(0)
        private Integer maxMaxRetries;

        @NotNull
        @Min(100)
        private Long defaultRetryDelayMs;

        @NotNull
        @Min(100)
        private Long maxRetryDelayMs;

        @NotNull
        @DecimalMin("1.0")
        private Double defaultRetryBackoffMultiplier;

        @NotNull
        @DecimalMin("1.0")
        private Double maxRetryBackoffMultiplier;
    }

    @Getter
    @Setter
    public static class RunInputs {

        @NotNull
        @Min(1)
        private Integer retentionDays;
    }
}
