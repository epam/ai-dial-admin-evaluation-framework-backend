package com.epam.aidial.evaluation.configuration.properties.analytics;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bounds for the suite test-case pass-rate endpoint.
 *
 * <p>{@code defaultLastN} is the number of most-recent test-suite runs considered when the client does not
 * supply {@code lastN}. {@code maxLastN} caps the client-supplied {@code lastN}; besides bounding response
 * size, it also bounds the number of run ids bound into the analytics {@code IN} clause used to fetch
 * pass-rate statistics.
 *
 * <p>Defaults live in {@code application.yml} only — never as a Java field initializer.
 */
@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "analytics.pass-rate")
public class PassRateProperties {

    @NotNull
    @Min(1)
    private Integer defaultLastN;

    @NotNull
    @Min(1)
    private Integer maxLastN;

    @AssertTrue(message = "analytics.pass-rate.default-last-n must not exceed analytics.pass-rate.max-last-n")
    public boolean isDefaultWithinMax() {
        return defaultLastN == null || maxLastN == null || defaultLastN <= maxLastN;
    }
}
