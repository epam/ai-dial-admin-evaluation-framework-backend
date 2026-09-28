package com.epam.aidial.evaluation.configuration.properties.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

@DisplayName("CsvImportProperties binding")
class CsvImportPropertiesTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfiguration.class);

    @Configuration
    @EnableConfigurationProperties(CsvImportProperties.class)
    static class TestConfiguration {}

    @Test
    @DisplayName("binds the real application.yml zip defaults: max-entries 1000, max-total-uncompressed-size 1GB")
    void applicationYmlDefaults_bindZipMaxEntriesAndMaxTotalUncompressedSize() throws Exception {
        final List<PropertySource<?>> applicationYml =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));

        runner.withInitializer(context -> applicationYml.forEach(
                        source -> context.getEnvironment().getPropertySources().addLast(source)))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final CsvImportProperties properties = context.getBean(CsvImportProperties.class);
                    assertThat(properties.getZip().getMaxEntries()).isEqualTo(1000);
                    assertThat(properties.getZip().getMaxTotalUncompressedSize())
                            .isEqualTo(DataSize.ofGigabytes(1));
                });
    }

    @Test
    @DisplayName("fails to start when zip.max-entries is below 1")
    void zipMaxEntriesBelowOne_bindingFails() {
        runner.withPropertyValues(
                        "csv.import.max-file-size=10MB",
                        "csv.import.max-rows=100000",
                        "csv.import.batch-size=1000",
                        "csv.import.zip.max-entries=0",
                        "csv.import.zip.max-total-uncompressed-size=1GB")
                .run(context ->
                        assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("maxEntries"));
    }

    @Test
    @DisplayName("fails to start when zip.max-total-uncompressed-size is missing")
    void zipMaxTotalUncompressedSizeMissing_bindingFails() {
        runner.withPropertyValues(
                        "csv.import.max-file-size=10MB",
                        "csv.import.max-rows=100000",
                        "csv.import.batch-size=1000",
                        "csv.import.zip.max-entries=1000")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("maxTotalUncompressedSize"));
    }
}
