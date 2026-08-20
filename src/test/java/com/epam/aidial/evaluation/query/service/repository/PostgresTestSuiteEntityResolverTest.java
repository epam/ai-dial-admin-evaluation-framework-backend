package com.epam.aidial.evaluation.query.service.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.epam.aidial.evaluation.data.db.repository.sql.json.PostgresJsonPathAccessor;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.JooqTableSchemaResolver;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PostgresTestSuiteEntityResolver")
class PostgresTestSuiteEntityResolverTest {

    private final PostgresTestSuiteEntityResolver resolver = new PostgresTestSuiteEntityResolver(
            mock(DSLContext.class), new JooqTableSchemaResolver(), new PostgresJsonPathAccessor());

    @Test
    @DisplayName("exposes entity name test_suites")
    void shouldExposeEntityName() {
        assertThat(resolver.entity()).isEqualTo("test_suites");
    }

    @Test
    @DisplayName("includes opaque deployment_ref and mcp_deployment_ref OBJECT bindings from the table")
    void shouldIncludeOpaqueObjectBindings() {
        final Map<String, QueryFieldBinding> bindings = resolver.bindings(mock(StructuredQuery.class));

        assertThat(bindings).containsKey("deployment_ref");
        assertThat(bindings.get("deployment_ref").type()).isEqualTo(QueryFieldType.OBJECT);

        assertThat(bindings).containsKey("mcp_deployment_ref");
        assertThat(bindings.get("mcp_deployment_ref").type()).isEqualTo(QueryFieldType.OBJECT);
    }

    @Test
    @DisplayName("exposes deployment_ref::id, ::name, ::version, ::type as STRING virtual sub-field bindings")
    void shouldExposeDeploymentRefSubFields() {
        final Map<String, QueryFieldBinding> bindings = resolver.bindings(mock(StructuredQuery.class));

        assertThat(bindings.get("deployment_ref::id").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("deployment_ref::name").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("deployment_ref::version").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("deployment_ref::type").type()).isEqualTo(QueryFieldType.STRING);
    }

    @Test
    @DisplayName("exposes mcp_deployment_ref::id, ::name, ::type, ::transport as STRING virtual sub-field bindings")
    void shouldExposeMcpDeploymentRefSubFields() {
        final Map<String, QueryFieldBinding> bindings = resolver.bindings(mock(StructuredQuery.class));

        assertThat(bindings.get("mcp_deployment_ref::id").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("mcp_deployment_ref::name").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("mcp_deployment_ref::type").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings.get("mcp_deployment_ref::transport").type()).isEqualTo(QueryFieldType.STRING);
    }

    @Test
    @DisplayName("includes standard table columns in the bindings map")
    void shouldIncludeStandardTableColumns() {
        final Map<String, QueryFieldBinding> bindings = resolver.bindings(mock(StructuredQuery.class));

        assertThat(bindings).containsKey("id");
        assertThat(bindings.get("id").type()).isEqualTo(QueryFieldType.UUID);
        assertThat(bindings).containsKey("name");
        assertThat(bindings.get("name").type()).isEqualTo(QueryFieldType.STRING);
        assertThat(bindings).containsKey("is_valid");
        assertThat(bindings.get("is_valid").type()).isEqualTo(QueryFieldType.BOOLEAN);
    }
}
