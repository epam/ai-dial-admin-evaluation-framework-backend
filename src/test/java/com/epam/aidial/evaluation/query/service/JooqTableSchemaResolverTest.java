package com.epam.aidial.evaluation.query.service;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;
import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITES;
import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.query.service.dto.QuerySchemaFieldDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises naming and type derivation against the real generated jOOQ tables, so a convention
 * regression (or a metadata shape change after {@code generateJooq}) fails here, not in production.
 */
class JooqTableSchemaResolverTest {

    private final JooqTableSchemaResolver resolver = new JooqTableSchemaResolver();

    @Test
    @DisplayName("maps VARCHAR(36) columns to uuid and other VARCHARs to string")
    void shouldMapVarchar36ToUuid() {
        List<QuerySchemaFieldDto> fields = resolver.resolve(TEST_SUITES);

        assertThat(fields)
                .contains(
                        new QuerySchemaFieldDto("id", QueryFieldType.UUID, "id"),
                        new QuerySchemaFieldDto("dataset_id", QueryFieldType.UUID, "dataset_id"),
                        new QuerySchemaFieldDto("name", QueryFieldType.STRING, "name"),
                        new QuerySchemaFieldDto("suite_type", QueryFieldType.STRING, "suite_type"));
    }

    @Test
    @DisplayName("maps JSONB columns to array when the DDL default is a JSON array, else object")
    void shouldMapJsonbByDdlDefault() {
        assertThat(resolver.resolve(TEST_SUITES))
                .contains(
                        new QuerySchemaFieldDto("response_columns", QueryFieldType.ARRAY, "response_columns"),
                        new QuerySchemaFieldDto("input_bindings", QueryFieldType.ARRAY, "input_bindings"),
                        new QuerySchemaFieldDto(
                                "disabled_test_case_ids", QueryFieldType.ARRAY, "disabled_test_case_ids"),
                        new QuerySchemaFieldDto("deployment_ref", QueryFieldType.OBJECT, "deployment_ref"),
                        new QuerySchemaFieldDto("request_template", QueryFieldType.OBJECT, "request_template"));
        assertThat(resolver.resolve(TEST_CASE_EVAL_SUMMARIES))
                .contains(
                        new QuerySchemaFieldDto("extraction_warnings", QueryFieldType.ARRAY, "extraction_warnings"),
                        new QuerySchemaFieldDto("test_case_data", QueryFieldType.OBJECT, "test_case_data"),
                        new QuerySchemaFieldDto("metric_values", QueryFieldType.OBJECT, "metric_values"));
    }

    @Test
    @DisplayName("publishes raw column names for epoch-millisecond timestamp and duration columns")
    void shouldPublishRawColumnNamesForTimestamps() {
        assertThat(resolver.resolve(TEST_CASE_EVAL_SUMMARIES))
                .contains(
                        new QuerySchemaFieldDto("created_at_ms", QueryFieldType.LONG, "created_at_ms"),
                        new QuerySchemaFieldDto("computed_at_ms", QueryFieldType.LONG, "computed_at_ms"),
                        new QuerySchemaFieldDto("exec_duration_ms", QueryFieldType.LONG, "exec_duration_ms"));
    }

    @Test
    @DisplayName("publishes raw column names for boolean columns including the is_ prefix")
    void shouldPublishRawColumnNamesForBooleans() {
        assertThat(resolver.resolve(TEST_SUITES))
                .contains(new QuerySchemaFieldDto("is_valid", QueryFieldType.BOOLEAN, "is_valid"));
    }

    @Test
    @DisplayName("maps integer columns and emits columns in DDL order")
    void shouldMapIntegerAndPreserveDdlOrder() {
        List<QuerySchemaFieldDto> fields = resolver.resolve(TEST_CASE_EVAL_SUMMARIES);

        assertThat(fields)
                .contains(
                        new QuerySchemaFieldDto("run_index", QueryFieldType.INTEGER, "run_index"),
                        new QuerySchemaFieldDto(
                                "response_status_code", QueryFieldType.INTEGER, "response_status_code"));
        assertThat(fields.getFirst().name()).isEqualTo("id");
    }

    @Test
    @DisplayName("applies name and type overrides by column name")
    void shouldApplyOverrides() {
        List<QuerySchemaFieldDto> fields = resolver.resolve(
                TEST_SUITES, Map.of("name", "suiteName"), Map.of("description", QueryFieldType.OBJECT));

        assertThat(fields)
                .contains(
                        new QuerySchemaFieldDto("suiteName", QueryFieldType.STRING, "suiteName"),
                        new QuerySchemaFieldDto("description", QueryFieldType.OBJECT, "description"));
    }
}
