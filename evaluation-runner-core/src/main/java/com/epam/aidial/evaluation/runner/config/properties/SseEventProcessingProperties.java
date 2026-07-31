package com.epam.aidial.evaluation.runner.config.properties;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Global, path-agnostic configuration for SSE event processing.
 *
 * <p>{@code maxTotalDurationMs} is the absolute cap on how long a single SSE stream may be parsed,
 * regardless of activity. It is the safety net behind the per-path idle (inactivity) timeout: a
 * server that heartbeats forever still terminates once total elapsed time crosses this cap. Shared
 * by both streaming paths (evaluation engine and Try It Out); the default lives in
 * {@code application.yml}.
 */
@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "sse-event-processing")
public class SseEventProcessingProperties {

    @NotNull
    @Min(1000)
    private Long maxTotalDurationMs;
}
