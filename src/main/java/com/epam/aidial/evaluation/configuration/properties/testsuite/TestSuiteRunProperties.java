package com.epam.aidial.evaluation.configuration.properties.testsuite;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "test-suite-run")
public class TestSuiteRunProperties {

    @NotNull
    @Valid
    private Executor executor;

    @NotNull
    @Valid
    private Sse sse;

    @NotNull
    @Valid
    private RunConfig runConfig;

    @NotNull
    @Valid
    private Limits limits;

    @Getter
    @Setter
    public static class Executor {

        @NotNull
        @Min(1)
        private Integer corePoolSize;

        @NotNull
        @Min(1)
        private Integer maxPoolSize;

        @NotNull
        @Min(0)
        private Integer queueCapacity;
    }

    @Getter
    @Setter
    public static class Sse {

        @NotNull
        @Min(1)
        private Integer timeoutMinutes;

        @NotNull
        @Min(1000)
        private Long cleanupIntervalMs;
    }

    @Getter
    @Setter
    public static class RunConfig {

        @NotNull
        @Min(1)
        private Integer maxNumberOfRuns;
    }

    @Getter
    @Setter
    public static class Limits {

        @NotNull
        @Min(1)
        private Integer maxConcurrentRunsGlobal;

        @NotNull
        @Min(1)
        private Integer maxConcurrentRunsPerSuite;
    }
}
