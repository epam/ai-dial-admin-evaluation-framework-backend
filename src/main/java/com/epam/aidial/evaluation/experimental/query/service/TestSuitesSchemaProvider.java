package com.epam.aidial.evaluation.experimental.query.service;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITES;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.experimental.query.service.dto.QuerySchemaFieldDto;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Schema provider for the simple {@code test_suites} entity. The schema is derived once from the
 * generated jOOQ {@code TEST_SUITES} table — JSONB-backed structures (refs, templates, bindings,
 * warnings) are listed as-is — so the entity is not complex and has no detailed schema.
 */
@Component
@LogExecution
public class TestSuitesSchemaProvider implements QueryableEntitySchemaProvider {

    static final String ENTITY_NAME = "test_suites";

    private static final QueryEntityDto DESCRIPTOR = new QueryEntityDto(ENTITY_NAME, false, null);

    private final List<QuerySchemaFieldDto> baseSchema;

    public TestSuitesSchemaProvider(JooqTableSchemaResolver schemaResolver) {
        this.baseSchema = schemaResolver.resolve(TEST_SUITES);
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
