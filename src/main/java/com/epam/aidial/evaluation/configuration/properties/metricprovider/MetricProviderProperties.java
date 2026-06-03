package com.epam.aidial.evaluation.configuration.properties.metricprovider;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Configuration
@LogExecution
@Validated
@ConfigurationProperties(prefix = "metric-providers")
public class MetricProviderProperties {

    /**
     * Map of metric provider entries keyed by provider id (base URL, timeouts).
     * Empty by default; sync does not run until at least one provider is configured and sync is enabled.
     */
    @NotNull
    @Valid
    private Map<String, ProviderEntry> providers = new LinkedHashMap<>();

    @NotNull
    @Valid
    private SyncSettings sync = new SyncSettings();

    @Getter
    @Setter
    public static class ProviderEntry {

        @NotBlank(message = "Metric provider base-url is required")
        private String baseUrl;

        @Min(0)
        private int connectTimeoutMs = 5000;

        @Min(0)
        private int readTimeoutMs = 30000;
    }

    @Getter
    @Setter
    public static class SyncSettings {

        /** When false, the metric provider sync job is not scheduled. */
        private boolean enabled = false;

        /** Cron expression for scheduled sync (e.g. every 5 minutes). Use "-" to disable recurring. */
        private String cron = "-";

        /** Fixed delay in milliseconds between sync runs. Optional if cron is set. */
        @Min(0)
        private long fixedDelayMs = 0;
    }
}
