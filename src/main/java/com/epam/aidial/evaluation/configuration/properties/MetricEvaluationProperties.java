package com.epam.aidial.evaluation.configuration.properties;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Configuration
@LogExecution
@Validated
@ConfigurationProperties(prefix = "metric-evaluation")
public class MetricEvaluationProperties {

    @NotNull
    @Min(1)
    private Integer defaultConcurrencyPerProvider;

    @NotNull
    @Min(1)
    private Integer batchSize;

    @NotNull
    @Min(1000)
    private Long perResultTimeoutMs;

    @NotNull
    @Valid
    private Retry retry;

    @Getter
    @Setter
    public static class Retry {

        @NotNull
        @Min(0)
        private Integer maxRetries;

        @NotNull
        @Min(100)
        private Long retryDelayMs;

        @NotNull
        @DecimalMin("1.0")
        private Double retryBackoffMultiplier;

        @NotNull
        @Min(100)
        private Long maxRetryDelayMs;
    }
}
