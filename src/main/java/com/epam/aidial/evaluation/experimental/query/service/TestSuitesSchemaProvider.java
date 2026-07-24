package com.epam.aidial.evaluation.experimental.query.service;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITES;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.experimental.query.service.dto.QuerySchemaFieldDto;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Schema provider for the simple {@code test_suites} entity. The schema is derived once from the
 * generated jOOQ {@code TEST_SUITES} table — JSONB-backed structures (refs, templates, bindings,
 * warnings) are listed as-is — so the entity is not complex and has no detailed schema.
 *
 * <p>In addition to the raw table columns, the following virtual sub-field entries are appended for
 * the {@code deployment_ref} and {@code mcp_deployment_ref} JSONB columns, enabling clients to
 * filter, project, and sort by individual deployment reference fields:
 * <ul>
 *   <li>{@code deployment_ref::id}, {@code deployment_ref::name}, {@code deployment_ref::version}, {@code deployment_ref::type}</li>
 *   <li>{@code mcp_deployment_ref::id}, {@code mcp_deployment_ref::name}, {@code mcp_deployment_ref::type}, {@code mcp_deployment_ref::transport}</li>
 * </ul>
 */
@Component
@LogExecution
public class TestSuitesSchemaProvider implements QueryableEntitySchemaProvider {

    static final String ENTITY_NAME = "test_suites";

    private static final QueryEntityDto DESCRIPTOR = new QueryEntityDto(ENTITY_NAME, false, null);

    private final List<QuerySchemaFieldDto> baseSchema;

    public TestSuitesSchemaProvider(JooqTableSchemaResolver schemaResolver) {
        final List<QuerySchemaFieldDto> schema = new ArrayList<>(schemaResolver.resolve(TEST_SUITES));
        schema.add(new QuerySchemaFieldDto("deployment_ref::id", QueryFieldType.STRING, "deployment_ref"));
        schema.add(new QuerySchemaFieldDto("deployment_ref::name", QueryFieldType.STRING, "deployment_ref"));
        schema.add(new QuerySchemaFieldDto("deployment_ref::version", QueryFieldType.STRING, "deployment_ref"));
        schema.add(new QuerySchemaFieldDto("deployment_ref::type", QueryFieldType.STRING, "deployment_ref"));
        schema.add(new QuerySchemaFieldDto("mcp_deployment_ref::id", QueryFieldType.STRING, "mcp_deployment_ref"));
        schema.add(new QuerySchemaFieldDto("mcp_deployment_ref::name", QueryFieldType.STRING, "mcp_deployment_ref"));
        schema.add(new QuerySchemaFieldDto("mcp_deployment_ref::type", QueryFieldType.STRING, "mcp_deployment_ref"));
        schema.add(
                new QuerySchemaFieldDto("mcp_deployment_ref::transport", QueryFieldType.STRING, "mcp_deployment_ref"));
        this.baseSchema = List.copyOf(schema);
    }

    @Override
    public QueryEntityDto descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<QuerySchemaFieldDto> baseSchema() {
        return baseSchema;
    }
}
