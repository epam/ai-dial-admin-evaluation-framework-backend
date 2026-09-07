package com.epam.aidial.evaluation.configuration.properties.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@DisplayName("AnalyticsPartitioningProperties binding")
class AnalyticsPartitioningPropertiesBindingTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

    @Configuration
    @EnableConfigurationProperties(AnalyticsPartitioningProperties.class)
    static class TestConfiguration {}

    private static ApplicationContextRunner withDefaults(ApplicationContextRunner runner) {
        return runner.withPropertyValues(
                "analytics.partitioning.enabled=true",
                "analytics.partitioning.look-ahead-months=2",
                "analytics.partitioning.retention-months=0",
                "analytics.partitioning.archive-instead-of-drop=false",
                "analytics.partitioning.maintenance-interval-ms=86400000",
                "analytics.partitioning.initial-delay-ms=60000");
    }

    @Test
    @DisplayName("binds all fields when every property is supplied")
    void allPropertiesSupplied_bindsSuccessfully() {
        withDefaults(runner).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(AnalyticsPartitioningProperties.class);
            assertThat(properties.getEnabled()).isTrue();
            assertThat(properties.getLookAheadMonths()).isEqualTo(2);
            assertThat(properties.getRetentionMonths()).isEqualTo(0);
            assertThat(properties.getArchiveInsteadOfDrop()).isFalse();
            assertThat(properties.getMaintenanceIntervalMs()).isEqualTo(86_400_000L);
            assertThat(properties.getInitialDelayMs()).isEqualTo(60_000L);
        });
    }

    @Test
    @DisplayName("fails to start when enabled is omitted")
    void enabledOmitted_bindingFails() {
        runner.withPropertyValues(
                        "analytics.partitioning.look-ahead-months=2",
                        "analytics.partitioning.retention-months=0",
                        "analytics.partitioning.archive-instead-of-drop=false",
                        "analytics.partitioning.maintenance-interval-ms=86400000",
                        "analytics.partitioning.initial-delay-ms=60000")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("enabled")
                        .hasMessageContaining("must not be null"));
    }

    @Test
    @DisplayName("fails to start when look-ahead-months is zero")
    void lookAheadMonthsZero_bindingFails() {
        withDefaults(runner)
                .withPropertyValues("analytics.partitioning.look-ahead-months=0")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("lookAheadMonths"));
    }

    @Test
    @DisplayName("accepts retention-months of zero (retention disabled)")
    void retentionMonthsZero_bindsSuccessfully() {
        withDefaults(runner).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("fails to start when retention-months is negative")
    void retentionMonthsNegative_bindingFails() {
        withDefaults(runner)
                .withPropertyValues("analytics.partitioning.retention-months=-1")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("retentionMonths"));
    }

    @Test
    @DisplayName("fails to start when maintenance-interval-ms is zero")
    void maintenanceIntervalMsZero_bindingFails() {
        withDefaults(runner)
                .withPropertyValues("analytics.partitioning.maintenance-interval-ms=0")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("maintenanceIntervalMs"));
    }
}
