package com.epam.aidial.evaluation.configuration.properties.analytics;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bounds for the matched-row run-comparison endpoint.
 *
 * <p>{@code maxUnmatchedRows} caps how many <strong>non-matching</strong> eval-summary rows a single
 * comparison may report per run. The cap exists for a concrete failure mode rather than tidiness: the
 * returned ids are bound into the structured query as one parameter each, and exceeding Postgres'
 * parameter ceiling surfaces as an uncategorized SQL exception (an HTTP 500), so the limit is turned into
 * an explicit 409 instead. It also bounds the worst-case response size.
 *
 * <p>Defaults live in {@code application.yml} only — never as a Java field initializer.
 */
@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "analytics.comparison")
public class RunComparisonProperties {

    @NotNull
    @Min(1)
    private Integer maxUnmatchedRows;
}
