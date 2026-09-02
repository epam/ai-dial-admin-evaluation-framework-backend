package com.epam.aidial.evaluation.configuration.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DatasourceValidationConfiguration")
class DatasourceValidationConfigurationTest {

    private final DatasourceValidationConfiguration configuration = new DatasourceValidationConfiguration();

    @Nested
    @DisplayName("parseJdbcUrl")
    class ParseJdbcUrl {

        @Test
        @DisplayName("shouldParsePostgresUrlWithExplicitPort")
        void shouldParsePostgresUrlWithExplicitPort() {
            var components =
                    DatasourceValidationConfiguration.parseJdbcUrl("jdbc:postgresql://db-host:5433/evaluation_db");

            assertThat(components.host()).isEqualTo("db-host");
            assertThat(components.port()).isEqualTo(5433);
            assertThat(components.database()).isEqualTo("evaluation_db");
        }

        @Test
        @DisplayName("shouldDefaultPostgresPortWhenOmitted")
        void shouldDefaultPostgresPortWhenOmitted() {
            var components = DatasourceValidationConfiguration.parseJdbcUrl("jdbc:postgresql://db-host/evaluation_db");

            assertThat(components.port()).isEqualTo(5432);
        }

        @Test
        @DisplayName("shouldParseChSchemeUrlWithExplicitPort")
        void shouldParseChSchemeUrlWithExplicitPort() {
            var components =
                    DatasourceValidationConfiguration.parseJdbcUrl("jdbc:ch://ch-host:9440/evaluation_analytics");

            assertThat(components.host()).isEqualTo("ch-host");
            assertThat(components.port()).isEqualTo(9440);
            assertThat(components.database()).isEqualTo("evaluation_analytics");
        }

        @Test
        @DisplayName("shouldParseClickhouseSchemeUrl")
        void shouldParseClickhouseSchemeUrl() {
            var components = DatasourceValidationConfiguration.parseJdbcUrl(
                    "jdbc:clickhouse://ch-host:8123/evaluation_analytics");

            assertThat(components.host()).isEqualTo("ch-host");
            assertThat(components.port()).isEqualTo(8123);
            assertThat(components.database()).isEqualTo("evaluation_analytics");
        }

        @Test
        @DisplayName("shouldDefaultClickhousePortToEightOneTwoThreeWhenOmitted")
        void shouldDefaultClickhousePortToEightOneTwoThreeWhenOmitted() {
            var components = DatasourceValidationConfiguration.parseJdbcUrl("jdbc:ch://ch-host/evaluation_analytics");

            assertThat(components.port()).isEqualTo(8123);
        }

        @Test
        @DisplayName("shouldStripQueryParamsFromChUrl")
        void shouldStripQueryParamsFromChUrl() {
            var components = DatasourceValidationConfiguration.parseJdbcUrl(
                    "jdbc:ch://ch-host:8123/evaluation_analytics?compress=0");

            assertThat(components.database()).isEqualTo("evaluation_analytics");
        }

        @Test
        @DisplayName("shouldRejectUnsupportedScheme")
        void shouldRejectUnsupportedScheme() {
            assertThatThrownBy(() -> DatasourceValidationConfiguration.parseJdbcUrl("jdbc:mysql://host:3306/db"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported JDBC URL format");
        }
    }

    @Nested
    @DisplayName("datasourceValidationResult")
    class DatasourceValidationResultBean {

        @Test
        @DisplayName("shouldAcceptClickhouseVendorWithDistinctDatasources")
        void shouldAcceptClickhouseVendorWithDistinctDatasources() {
            var result = configuration.datasourceValidationResult(
                    "jdbc:postgresql://meta-host:5432/evaluation_db",
                    "jdbc:postgresql://analytics-host:5432/evaluation_analytics_db",
                    "jdbc:ch://ch-host:8123/evaluation_analytics",
                    "public",
                    "public",
                    "CLICKHOUSE");

            assertThat(result.validated()).isTrue();
        }

        @Test
        @DisplayName("shouldRejectUnsupportedVendor")
        void shouldRejectUnsupportedVendor() {
            assertThatThrownBy(() -> configuration.datasourceValidationResult(
                            "jdbc:postgresql://meta-host:5432/evaluation_db",
                            "jdbc:postgresql://analytics-host:5432/evaluation_analytics_db",
                            "",
                            "public",
                            "public",
                            "MYSQL"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not yet supported");
        }

        @Test
        @DisplayName("shouldRejectClickhouseVendorCollidingWithMetaOnSameHostPortDatabaseAndSchema")
        void shouldRejectClickhouseVendorCollidingWithMetaOnSameHostPortDatabaseAndSchema() {
            assertThatThrownBy(() -> configuration.datasourceValidationResult(
                            "jdbc:postgresql://shared-host:8123/shared_db",
                            "jdbc:postgresql://analytics-host:5432/evaluation_analytics_db",
                            "jdbc:ch://shared-host:8123/shared_db",
                            "public",
                            "public",
                            "CLICKHOUSE"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must use separate databases or separate schemas");
        }
    }
}
