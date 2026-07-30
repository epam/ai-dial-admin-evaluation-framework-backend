package com.epam.aidial.evaluation.configuration.properties;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Runtime bounds applied to every JSONata expression evaluation via
 * {@code com.dashjoin.jsonata.Jsonata.Frame#setRuntimeBounds}, protecting worker threads from a
 * runaway or unbounded-recursion JSONata expression. Applied uniformly to request-template and
 * response-column/condition evaluation. Defaults live in {@code application.yml}.
 */
@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "jsonata")
public class JsonataProperties {

    @NotNull
    @Min(1)
    private Long evaluationTimeoutMs;

    @NotNull
    @Min(1)
    private Integer maxRecursionDepth;
}
