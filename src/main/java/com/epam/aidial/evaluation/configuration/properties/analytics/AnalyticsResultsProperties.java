package com.epam.aidial.evaluation.configuration.properties.analytics;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@LogExecution
@ConfigurationProperties(prefix = "analytics.results")
public class AnalyticsResultsProperties {

    private Batch batch = new Batch();

    @Getter
    @Setter
    public static class Batch {
        private int maxItems;
        private long maxRequestSizeBytes;
    }
}
