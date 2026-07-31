package com.epam.aidial.evaluation.configuration.properties.analytics;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "analytics.results")
public class AnalyticsResultsProperties {

    private Batch batch = new Batch();
    private CsvImport csvImport = new CsvImport();

    @Getter
    @Setter
    public static class Batch {
        private int maxItems;
        private long maxRequestSizeBytes;
    }

    @Getter
    @Setter
    public static class CsvImport {
        @NotNull
        private DataSize maxFileSize;
    }
}
