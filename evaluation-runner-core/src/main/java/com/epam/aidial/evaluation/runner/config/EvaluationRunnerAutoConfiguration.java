package com.epam.aidial.evaluation.runner.config;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.DialCoreProperties;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.runner.config.properties.JsonataProperties;
import com.epam.aidial.evaluation.runner.config.properties.McpClientProperties;
import com.epam.aidial.evaluation.runner.config.properties.SseEventProcessingProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

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
public class EvaluationRunnerAutoConfiguration {}
