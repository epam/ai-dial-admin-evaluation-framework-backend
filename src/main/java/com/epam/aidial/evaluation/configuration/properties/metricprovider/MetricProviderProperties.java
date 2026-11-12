package com.epam.aidial.evaluation.configuration.properties.metricprovider;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "metric-providers")
public class MetricProviderProperties {

    /**
     * Map of metric provider entries keyed by provider id (enabled flag, base URL, timeouts).
     * Empty by default. Sync runs for an entry only when sync is enabled globally and that entry's
     * own enabled flag is true; a disabled entry stays configured but is skipped by the sync job.
     */
    @NotNull
    @Valid
    private Map<String, ProviderEntry> providers = new LinkedHashMap<>();

    /** Timeouts applied to every provider entry that does not set its own. */
    @NotNull
    @Valid
    private Defaults defaults = new Defaults();

    @NotNull
    @Valid
    private SyncSettings sync = new SyncSettings();

    /** Connect timeout for {@code entry}: its own value, else {@code defaults.connect-timeout-ms}. */
    public int resolveConnectTimeoutMs(ProviderEntry entry) {
        return entry.getConnectTimeoutMs() != null ? entry.getConnectTimeoutMs() : defaults.getConnectTimeoutMs();
    }

    /** Read timeout for {@code entry}: its own value, else {@code defaults.read-timeout-ms}. */
    public int resolveReadTimeoutMs(ProviderEntry entry) {
        return entry.getReadTimeoutMs() != null ? entry.getReadTimeoutMs() : defaults.getReadTimeoutMs();
    }

    @Getter
    @Setter
    public static class ProviderEntry {
        @NotNull
        private Boolean enabled;

        @NotBlank(message = "Metric provider base-url is required")
        private String baseUrl;

        /** Optional; falls back to {@code defaults.connect-timeout-ms}. */
        @Min(0)
        private Integer connectTimeoutMs;

        /** Optional; falls back to {@code defaults.read-timeout-ms}. */
        @Min(0)
        private Integer readTimeoutMs;
    }

    @Getter
    @Setter
    public static class Defaults {
        @NotNull
        @Min(0)
        private Integer connectTimeoutMs;

        @NotNull
        @Min(0)
        private Integer readTimeoutMs;
    }

    @Getter
    @Setter
    public static class SyncSettings {

        /** When false, the metric provider sync job is not scheduled. */
        private boolean enabled;

        /** Cron expression for scheduled sync (e.g. every 5 minutes). Use "-" to disable recurring. */
        private String cron;
    }
}
