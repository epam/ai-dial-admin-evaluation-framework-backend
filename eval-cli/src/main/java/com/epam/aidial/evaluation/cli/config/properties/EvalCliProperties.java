package com.epam.aidial.evaluation.cli.config.properties;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Top-level CLI configuration properties.
 *
 * <p>All defaults are defined in {@code application.yml}; no Java field-initializer defaults are used
 * per AGENTS.md convention. Suite selection and the clone suffix are deliberately NOT here: they are
 * required CLI options only ({@code --suites}/{@code --clone-suffix}, see {@code SuitesOption}/
 * {@code CloneSuffixOption}), with no configuration fallback.
 */
@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "cli")
public class EvalCliProperties {

    /** Working directory for persisted suite/test-case bundles from the fetch step. */
    @NotBlank
    private String workDir;

    /** Optional test-run name written into the imported run's metadata. */
    private String testRunName;

    @NotNull
    @Valid
    private Run run;

    @Getter
    @Setter
    public static class Run {

        @NotNull
        @Min(1)
        private Integer concurrencyLevel;

        /** Optional rate limit in requests per second. Null means unbounded. */
        private Double rateLimitRps;

        @NotNull
        @Min(1000)
        private Long requestTimeoutMs;

        @NotNull
        @Min(0)
        private Integer maxRetries;

        @NotNull
        @Min(0)
        private Long retryDelayMs;

        @NotNull
        @DecimalMin("1.0")
        private Double retryBackoffMultiplier;

        @NotNull
        @Min(0)
        private Long maxRetryDelayMs;

        @NotNull
        @Min(1)
        private Integer resultBatchSize;

        @NotNull
        @Min(1)
        private Long maxResponseSizeBytes;

        @NotNull
        @Min(0)
        private Long cancellationGracePeriodMs;
    }
}
