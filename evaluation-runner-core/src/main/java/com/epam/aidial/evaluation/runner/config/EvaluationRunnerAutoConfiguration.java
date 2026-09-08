package com.epam.aidial.evaluation.runner.config;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.DialCoreProperties;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.runner.config.properties.JsonataProperties;
import com.epam.aidial.evaluation.runner.config.properties.McpClientProperties;
import com.epam.aidial.evaluation.runner.config.properties.SseEventProcessingProperties;
import com.epam.aidial.evaluation.runner.job.RunExecutorFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.thread.Threading;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.env.Environment;

/**
 * Contributes every shared bean in {@code com.epam.aidial.evaluation.runner} to any consumer
 * application (the EF backend today, a future standalone CI runner tomorrow) via Spring Boot's
 * autoconfiguration mechanism — a consumer needs no manual {@code @Import}/{@code @ComponentScan} beyond
 * declaring the {@code evaluation-runner-core} dependency (see Decision 6 in the
 * {@code evaluation-runner-core-module} change's {@code design.md}).
 */
@AutoConfiguration
@LogExecution
@ComponentScan("com.epam.aidial.evaluation.runner")
@EnableConfigurationProperties({
    EvaluationRunProperties.class,
    SseEventProcessingProperties.class,
    DialCoreProperties.class,
    McpClientProperties.class,
    DialFileStorageProperties.class,
    JsonataProperties.class
})
public class EvaluationRunnerAutoConfiguration {

    /**
     * Reads Spring Boot's {@code spring.threads.virtual.enabled} switch so the run thread mode
     * (see {@link RunExecutorFactory}) follows the same JVM-wide setting Boot uses for its own
     * executors. A consumer application may define its own {@link RunExecutorFactory} bean to override.
     */
    @Bean
    @ConditionalOnMissingBean
    public RunExecutorFactory runExecutorFactory(Environment environment) {
        return new RunExecutorFactory(Threading.VIRTUAL.isActive(environment));
    }
}
