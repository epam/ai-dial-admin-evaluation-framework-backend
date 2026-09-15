package com.epam.aidial.evaluation.query.service.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.epam.aidial.evaluation.data.db.repository.sql.json.PostgresJsonPathAccessor;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.JooqTableSchemaResolver;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PostgresTestSuiteRunEntityResolver")
class PostgresTestSuiteRunEntityResolverTest {

    private static final List<String> EXPECTED_FIELDS = List.of(
            "id",
            "test_suite_id",
            "test_run_name",
            "status",
            "number_of_test_cases",
            "started_at_ms",
            "completed_at_ms",
            "error_message",
            "created_at_ms",
            "updated_at_ms",
            "suite_type",
            "deployment_ref::id",
            "deployment_ref::name",
            "deployment_ref::version",
            "deployment_ref::type",
            "mcp_deployment_ref::id",
            "mcp_deployment_ref::name",
            "mcp_deployment_ref::type",
            "mcp_deployment_ref::transport",
            "metric_names");

    private final PostgresTestSuiteRunEntityResolver resolver = new PostgresTestSuiteRunEntityResolver(
            mock(DSLContext.class), new JooqTableSchemaResolver(), new PostgresJsonPathAccessor());

    @Test
    @DisplayName("exposes entity name test_suite_runs")
    void shouldExposeEntityName() {
        assertThat(resolver.entity()).isEqualTo("test_suite_runs");
    }

    @Test
    @DisplayName("bindings contain exactly the 20 spec'd field names and none of the excluded columns")
    void shouldExposeExactlyTheSpecFieldSet() {
        final Map<String, QueryFieldBinding> bindings = resolver.bindings(mock(StructuredQuery.class));

        assertThat(bindings.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_FIELDS);
        assertThat(bindings).doesNotContainKeys("suite_snapshot", "run_config", "error_details");
    }

    @Test
    @DisplayName("every binding resolves to a non-null field on the derived table")
    void shouldResolveEveryBindingToNonNullField() {
        final Map<String, QueryFieldBinding> bindings = resolver.bindings(mock(StructuredQuery.class));

        for (final String field : EXPECTED_FIELDS) {
            assertThat(bindings.get(field).field())
                    .as("binding field for %s", field)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("types plain columns per the generated table and virtual fields explicitly")
    void shouldTypeFieldsPerSpec() {
        final Map<String, QueryFieldBinding> bindings = resolver.bindings(mock(StructuredQuery.class));

        assertThat(bindings.get("id").type()).isEqualTo(QueryFieldType.UUID);
        assertThat(bindings.get("test_suite_id").type()).isEqualTo(QueryFieldType.UUID);
        assertThat(bindings.get("test_run_name").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("status").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("number_of_test_cases").type()).isEqualTo(QueryFieldType.INTEGER);
        assertThat(bindings.get("started_at_ms").type()).isEqualTo(QueryFieldType.LONG);
        assertThat(bindings.get("completed_at_ms").type()).isEqualTo(QueryFieldType.LONG);
        assertThat(bindings.get("error_message").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("created_at_ms").type()).isEqualTo(QueryFieldType.LONG);
        assertThat(bindings.get("updated_at_ms").type()).isEqualTo(QueryFieldType.LONG);
        assertThat(bindings.get("suite_type").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("deployment_ref::id").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("deployment_ref::name").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("deployment_ref::version").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("deployment_ref::type").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("mcp_deployment_ref::id").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("mcp_deployment_ref::name").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("mcp_deployment_ref::type").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("mcp_deployment_ref::transport").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("metric_names").type()).isEqualTo(QueryFieldType.ARRAY);
    }

    @Test
    @DisplayName("derived table projects exactly the 20 spec'd field names")
    void shouldProjectExactlyTheSpecFieldSet() {
        final List<String> fieldNames =
                List.of(resolver.table().fields()).stream().map(Field::getName).toList();

        assertThat(fieldNames).containsExactlyInAnyOrderElementsOf(EXPECTED_FIELDS);
    }

    @Test
    @DisplayName("rendered SQL uses a correlated latest-computation metric_names subquery and excludes "
            + "run_config/error_details from the projection (suite_snapshot legitimately appears as extraction source)")
    void shouldRenderCorrelatedMetricNamesSubqueryAndExcludeRunConfigAndErrorDetails() {
        final DSLContext postgres = DSL.using(SQLDialect.POSTGRES);
        final String sql =
                postgres.render(postgres.selectFrom(resolver.table())).toLowerCase(Locale.ROOT);

        // jOOQ 3.21's default POSTGRES rendering emits the ANSI "fetch next ... rows only" form for
        // .limit(1) rather than the native "limit 1" keyword; both cap the correlated subquery to one
        // row, which is what this assertion verifies.
        assertThat(sql)
                .contains("jsonb_agg(distinct \"rms\".\"tsmd_name\" order by \"rms\".\"tsmd_name\")")
                .contains("\"computed_at_ms\" desc")
                .contains("\"computation_id\" desc")
                .contains("fetch next")
                .contains("rows only")
                .contains("coalesce(")
                .doesNotContain("run_config")
                .doesNotContain("error_details");
    }
}
