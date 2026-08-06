package com.epam.aidial.evaluation.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.cli.client.target.TargetDialCoreClientConfiguration;
import com.epam.aidial.evaluation.cli.config.ClockConfiguration;
import com.epam.aidial.evaluation.cli.config.properties.EvalCliProperties;
import com.epam.aidial.evaluation.cli.config.properties.SourceProperties;
import com.epam.aidial.evaluation.cli.config.properties.TargetProperties;
import com.epam.aidial.evaluation.runner.config.EvaluationRunnerAutoConfiguration;
import com.epam.aidial.evaluation.runner.job.TestCaseRunnerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Verifies that {@link EvaluationRunnerAutoConfiguration}'s beans (including
 * {@link com.epam.aidial.evaluation.runner.client.dialcore.DialCoreDeploymentInvoker} and
 * {@link TestCaseRunnerFactory}) resolve correctly when {@link TargetDialCoreClientConfiguration}
 * provides the {@code "dialCoreTryOutRestClient"} and {@code "dialFileRestClient"} beans.
 */
@SpringBootTest(
        classes = ContextStartupTest.TestConfig.class,
        properties = {"spring.main.web-application-type=none", "spring.main.allow-bean-definition-overriding=true"})
@TestPropertySource(
        properties = {
            "eval.source.base-url=http://source-ef:8080",
            "eval.source.token=source-token",
            "cli.work-dir=/tmp/work",
            "cli.run.concurrency-level=4",
            "cli.run.request-timeout-ms=3600000",
            "cli.run.max-retries=3",
            "cli.run.retry-delay-ms=1000",
            "cli.run.retry-backoff-multiplier=2.0",
            "cli.run.max-retry-delay-ms=30000",
            "cli.run.result-batch-size=50",
            "cli.run.max-response-size-bytes=10485760",
            "cli.run.cancellation-grace-period-ms=30000",
            "dial.components.core.base-url=http://dial:8085",
            "dial.components.core.api-key=target-api-key",
            "dial.components.core.retry.max-attempts=3",
            "dial.components.core.retry.delay-ms=1000",
            "dial.components.core.retry.multiplier=2.0",
            "dial.components.core.try-out.read-timeout-ms=3600000",
            "dial.mcp.connect-timeout-ms=5000",
            "dial.mcp.read-timeout-ms=120000",
            "dial.file-storage.bucket-alias=@ef",
            "dial.file-storage.max-file-size-bytes=52428800",
            "dial.file-storage.max-files-per-suite=100",
            "dial.file-storage.max-files-per-dataset=100",
            "dial.file-storage.connect-timeout-ms=5000",
            "dial.file-storage.read-timeout-ms=30000",
            "sse-event-processing.max-total-duration-ms=3600000",
            "test-suite-run.execution.default-concurrency-level=4",
            "test-suite-run.execution.max-concurrency-level=20",
            "test-suite-run.execution.default-request-timeout-ms=3600000",
            "test-suite-run.execution.max-request-timeout-ms=3600000",
            "test-suite-run.execution.result-batch-size=50",
            "test-suite-run.execution.max-response-size-bytes=10485760",
            "test-suite-run.execution.cancellation-grace-period-ms=30000",
            "test-suite-run.execution.header-blacklist=",
            "test-suite-run.retry.default-max-retries=3",
            "test-suite-run.retry.max-max-retries=10",
            "test-suite-run.retry.default-retry-delay-ms=1000",
            "test-suite-run.retry.max-retry-delay-ms=30000",
            "test-suite-run.retry.default-retry-backoff-multiplier=2.0",
            "test-suite-run.retry.max-retry-backoff-multiplier=10.0",
            "test-suite-run.run-inputs.retention-days=7"
        })
class ContextStartupTest {

    // Restrict scan to config.* and client.target.* only: avoids pulling in commands/services that
    // require additional beans (sourceRestClient) not relevant to this test's purpose.
    @SpringBootApplication(
            scanBasePackages = {"com.epam.aidial.evaluation.cli.config", "com.epam.aidial.evaluation.cli.client.target"
            })
    @Import({EvaluationRunnerAutoConfiguration.class, TargetDialCoreClientConfiguration.class, ClockConfiguration.class
    })
    @EnableConfigurationProperties({SourceProperties.class, EvalCliProperties.class, TargetProperties.class})
    static class TestConfig {

        // The ObjectMapper/JsonMapper bean required by evaluation-runner-core's
        // DialCoreDeploymentInvoker comes from JsonMapperConfiguration, and both
        // "dialCoreTryOutRestClient"/"dialFileRestClient" come from TargetDialCoreClientConfiguration —
        // both auto-detected/@Import-ed above, no manual stubs needed.
    }

    @Autowired
    private TestCaseRunnerFactory testCaseRunnerFactory;

    @Autowired(required = false)
    private RestClient dialCoreTryOutRestClient;

    @Autowired(required = false)
    @Qualifier("dialFileRestClient")
    private RestClient dialFileRestClient;

    @Test
    @DisplayName("dialCoreTryOutRestClient bean is present in context")
    void dialCoreTryOutRestClientBeanIsPresent() {
        assertThat(dialCoreTryOutRestClient).isNotNull();
    }

    @Test
    @DisplayName("dialFileRestClient bean is present in context")
    void dialFileRestClientBeanIsPresent() {
        assertThat(dialFileRestClient).isNotNull();
    }

    @Test
    @DisplayName("TestCaseRunnerFactory from evaluation-runner-core resolves correctly")
    void testCaseRunnerFactoryResolvesCorrectly() {
        assertThat(testCaseRunnerFactory).isNotNull();
    }
}
