package com.epam.aidial.evaluation.configuration.properties.dial;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "dial.components.core")
public class DialCoreProperties {

    @NotBlank
    private String baseUrl;

    @Min(0)
    private int connectTimeoutMs;

    @Min(0)
    private int readTimeoutMs;

    @NotNull
    @Valid
    private Retry retry;

    @NotNull
    @Valid
    private TryOut tryOut;

    @Getter
    @Setter
    public static class Retry {
        @Min(1)
        private int maxAttempts;

        @Min(0)
        private long delayMs;

        @Min(1)
        private double multiplier;
    }

    @Getter
    @Setter
    public static class TryOut {
        @Min(0)
        private int readTimeoutMs;
    }
}
