package com.epam.aidial.evaluation.experimental.query.service.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITES;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.repository.sql.json.JsonPathAccessor;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.service.JooqTableSchemaResolver;
import com.epam.aidial.evaluation.experimental.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Resolves the {@code test_suites} entity to the generated {@code TEST_SUITES} table on the meta
 * datasource ({@code metaDsl}). Field bindings are static per-table metadata, computed once here.
 *
 * <p>In addition to the raw jOOQ table columns, the following virtual sub-field bindings are added
 * for the {@code deployment_ref} and {@code mcp_deployment_ref} JSONB columns:
 * {@code deployment_ref::id}, {@code deployment_ref::name}, {@code deployment_ref::version},
 * {@code deployment_ref::type},
 * {@code mcp_deployment_ref::id}, {@code mcp_deployment_ref::name}, {@code mcp_deployment_ref::type},
 * {@code mcp_deployment_ref::transport}.
 * Each resolves to a {@code jsonb ->> 'key'} text extraction.
 */
@Repository
@LogExecution
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresTestSuiteEntityResolver implements StructuredQueryEntityResolver {

    private static final String ENTITY = "test_suites";

    private final DSLContext dsl;
    private final Map<String, QueryFieldBinding> bindings;

    public PostgresTestSuiteEntityResolver(
            @Qualifier("metaDsl") DSLContext dsl,
            JooqTableSchemaResolver schemaResolver,
            JsonPathAccessor jsonPathAccessor) {
        this.dsl = dsl;
        final Map<String, QueryFieldBinding> map = new LinkedHashMap<>(schemaResolver.bindings(TEST_SUITES));
        addSubField(map, jsonPathAccessor, "deployment_ref::id", TEST_SUITES.DEPLOYMENT_REF, "id");
        addSubField(map, jsonPathAccessor, "deployment_ref::name", TEST_SUITES.DEPLOYMENT_REF, "name");
        addSubField(map, jsonPathAccessor, "deployment_ref::version", TEST_SUITES.DEPLOYMENT_REF, "version");
        addSubField(map, jsonPathAccessor, "deployment_ref::type", TEST_SUITES.DEPLOYMENT_REF, "type");
        addSubField(map, jsonPathAccessor, "mcp_deployment_ref::id", TEST_SUITES.MCP_DEPLOYMENT_REF, "id");
        addSubField(map, jsonPathAccessor, "mcp_deployment_ref::name", TEST_SUITES.MCP_DEPLOYMENT_REF, "name");
        addSubField(map, jsonPathAccessor, "mcp_deployment_ref::type", TEST_SUITES.MCP_DEPLOYMENT_REF, "type");
        addSubField(
                map, jsonPathAccessor, "mcp_deployment_ref::transport", TEST_SUITES.MCP_DEPLOYMENT_REF, "transport");
        this.bindings = Map.copyOf(map);
    }

    @Override
    public String entity() {
        return ENTITY;
    }

    @Override
    public DSLContext dsl() {
        return dsl;
    }

    @Override
    public Table<?> table() {
        return TEST_SUITES;
    }

    @Override
    public Map<String, QueryFieldBinding> bindings(StructuredQuery query) {
        return bindings;
    }

    private static void addSubField(
            Map<String, QueryFieldBinding> map,
            JsonPathAccessor jsonPathAccessor,
            String fieldName,
            Field<JSONB> column,
            String key) {
        map.put(
                fieldName,
                new QueryFieldBinding(
                        fieldName, jsonPathAccessor.jsonbAtAsText(column, DSL.val(key)), QueryFieldType.STRING));
    }
}
