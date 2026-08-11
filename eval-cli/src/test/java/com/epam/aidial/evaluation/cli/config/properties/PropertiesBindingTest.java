package com.epam.aidial.evaluation.cli.config.properties;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = PropertiesBindingTest.TestConfig.class, properties = "spring.main.web-application-type=none")
@TestPropertySource(
        properties = {
            "eval.source.base-url=http://source-ef:8080",
            "eval.source.api-key=source-api-key",
            "cli.work-dir=/tmp/work",
            "dial.components.core.api-key=target-api-key",
            "cli.run.concurrency-level=4",
            "cli.run.request-timeout-ms=3600000",
            "cli.run.max-retries=3",
            "cli.run.retry-delay-ms=1000",
            "cli.run.retry-backoff-multiplier=2.0",
            "cli.run.max-retry-delay-ms=30000",
            "cli.run.result-batch-size=50",
            "cli.run.max-response-size-bytes=10485760",
            "cli.run.cancellation-grace-period-ms=30000"
        })
class PropertiesBindingTest {

    // Minimal config: only bind the CLI properties.
    // Using @Configuration (not @SpringBootApplication) avoids triggering Spring Boot
    // autoconfiguration, so evaluation-runner-core beans that need additional wiring are not loaded.
    @Configuration
    @EnableConfigurationProperties({SourceProperties.class, EvalCliProperties.class, TargetProperties.class})
    static class TestConfig {}

    @Autowired
    private SourceProperties sourceProperties;

    @Autowired
    private EvalCliProperties evalCliProperties;

    @Autowired
    private TargetProperties targetProperties;

    @Test
    void sourcePropertiesBindCorrectly() {
        assertThat(sourceProperties.getBaseUrl()).isEqualTo("http://source-ef:8080");
        assertThat(sourceProperties.getApiKey()).isEqualTo("source-api-key");
    }

    @Test
    void targetPropertiesBindCorrectly() {
        assertThat(targetProperties.getApiKey()).isEqualTo("target-api-key");
    }

    @Test
    void evalCliPropertiesBindCorrectly() {
        assertThat(evalCliProperties.getWorkDir()).isEqualTo("/tmp/work");
        assertThat(evalCliProperties.getRun().getConcurrencyLevel()).isEqualTo(4);
        assertThat(evalCliProperties.getRun().getRequestTimeoutMs()).isEqualTo(3600000L);
        assertThat(evalCliProperties.getRun().getMaxRetries()).isEqualTo(3);
        assertThat(evalCliProperties.getRun().getRetryDelayMs()).isEqualTo(1000L);
        assertThat(evalCliProperties.getRun().getRetryBackoffMultiplier()).isEqualTo(2.0);
        assertThat(evalCliProperties.getRun().getMaxRetryDelayMs()).isEqualTo(30000L);
        assertThat(evalCliProperties.getRun().getResultBatchSize()).isEqualTo(50);
        assertThat(evalCliProperties.getRun().getMaxResponseSizeBytes()).isEqualTo(10485760L);
        assertThat(evalCliProperties.getRun().getCancellationGracePeriodMs()).isEqualTo(30000L);
    }
}
