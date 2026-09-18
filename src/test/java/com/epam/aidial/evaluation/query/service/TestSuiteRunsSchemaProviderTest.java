package com.epam.aidial.evaluation.query.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.epam.aidial.evaluation.data.db.repository.sql.json.PostgresJsonPathAccessor;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.query.service.dto.QuerySchemaFieldDto;
import com.epam.aidial.evaluation.query.service.repository.PostgresTestSuiteRunEntityResolver;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TestSuiteRunsSchemaProvider")
class TestSuiteRunsSchemaProviderTest {

    private final TestSuiteRunsSchemaProvider provider = new TestSuiteRunsSchemaProvider(new JooqTableSchemaResolver());

    @Test
    @DisplayName("describes test_suite_runs as a simple entity without a schema id field")
    void shouldDescribeSimpleEntity() {
        assertThat(provider.descriptor()).isEqualTo(new QueryEntityDto("test_suite_runs", false, null));
    }

    @Test
    @DisplayName("base schema excludes suite_snapshot, run_config and error_details")
    void shouldExcludeHeavyJsonbColumns() {
        assertThat(provider.baseSchema())
                .extracting(QuerySchemaFieldDto::name)
                .doesNotContain("suite_snapshot", "run_config", "error_details");
    }

    @Test
    @DisplayName("base schema includes the plain run columns")
    void shouldIncludePlainColumns() {
        assertThat(provider.baseSchema())
                .contains(
                        new QuerySchemaFieldDto("id", QueryFieldType.UUID, "id"),
                        new QuerySchemaFieldDto("test_suite_id", QueryFieldType.UUID, "test_suite_id"),
                        new QuerySchemaFieldDto("test_run_name", QueryFieldType.STRING, "test_run_name"),
                        new QuerySchemaFieldDto("status", QueryFieldType.STRING, "status"),
                        new QuerySchemaFieldDto("number_of_test_cases", QueryFieldType.INTEGER, "number_of_test_cases"),
                        new QuerySchemaFieldDto("started_at_ms", QueryFieldType.LONG, "started_at_ms"),
                        new QuerySchemaFieldDto("completed_at_ms", QueryFieldType.LONG, "completed_at_ms"),
                        new QuerySchemaFieldDto("error_message", QueryFieldType.STRING, "error_message"),
                        new QuerySchemaFieldDto("created_at_ms", QueryFieldType.LONG, "created_at_ms"),
                        new QuerySchemaFieldDto("updated_at_ms", QueryFieldType.LONG, "updated_at_ms"));
    }

    @Test
    @DisplayName("base schema includes number_of_runs sourced from run_config")
    void shouldIncludeNumberOfRuns() {
        assertThat(provider.baseSchema())
                .contains(new QuerySchemaFieldDto("number_of_runs", QueryFieldType.INTEGER, "run_config"));
    }

    @Test
    @DisplayName("base schema includes suite_type and the deployment_ref/mcp_deployment_ref virtual "
            + "sub-fields sourced from suite_snapshot")
    void shouldIncludeSnapshotVirtualFields() {
        assertThat(provider.baseSchema())
                .contains(
                        new QuerySchemaFieldDto("suite_type", QueryFieldType.STRING, "suite_snapshot"),
                        new QuerySchemaFieldDto("deployment_ref::id", QueryFieldType.STRING, "suite_snapshot"),
                        new QuerySchemaFieldDto("deployment_ref::name", QueryFieldType.STRING, "suite_snapshot"),
                        new QuerySchemaFieldDto("deployment_ref::version", QueryFieldType.STRING, "suite_snapshot"),
                        new QuerySchemaFieldDto("deployment_ref::type", QueryFieldType.STRING, "suite_snapshot"),
                        new QuerySchemaFieldDto("mcp_deployment_ref::id", QueryFieldType.STRING, "suite_snapshot"),
                        new QuerySchemaFieldDto("mcp_deployment_ref::name", QueryFieldType.STRING, "suite_snapshot"),
                        new QuerySchemaFieldDto("mcp_deployment_ref::type", QueryFieldType.STRING, "suite_snapshot"),
                        new QuerySchemaFieldDto(
                                "mcp_deployment_ref::transport", QueryFieldType.STRING, "suite_snapshot"));
    }

    @Test
    @DisplayName("base schema includes metric_names as an ARRAY sourced from run_metric_snapshots")
    void shouldIncludeMetricNames() {
        assertThat(provider.baseSchema())
                .contains(new QuerySchemaFieldDto("metric_names", QueryFieldType.ARRAY, "run_metric_snapshots"));
    }

    @Test
    @DisplayName("has no detailed schema, being a simple entity")
    void shouldThrowUnsupported_whenDetailedSchemaRequested() {
        assertThatThrownBy(() -> provider.detailedSchema(Map.of("test_suite_id", "any-id")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("schema field-name set and types are in parity with the resolver's bindings")
    void shouldBeInParityWithTheResolverBindings() {
        final PostgresTestSuiteRunEntityResolver resolver = new PostgresTestSuiteRunEntityResolver(
                mock(DSLContext.class), new JooqTableSchemaResolver(), new PostgresJsonPathAccessor());
        final Map<String, QueryFieldBinding> bindings = resolver.bindings(mock(StructuredQuery.class));
        final List<QuerySchemaFieldDto> schema = provider.baseSchema();

        assertThat(schema.stream().map(QuerySchemaFieldDto::name).collect(Collectors.toSet()))
                .isEqualTo(bindings.keySet());

        for (final QuerySchemaFieldDto field : schema) {
            assertThat(bindings.get(field.name()).type())
                    .as("type of field %s", field.name())
                    .isEqualTo(field.type());
        }
    }
}
