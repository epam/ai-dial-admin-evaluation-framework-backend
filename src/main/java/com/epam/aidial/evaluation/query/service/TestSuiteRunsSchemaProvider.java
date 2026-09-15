package com.epam.aidial.evaluation.query.service;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITE_RUNS;
import static com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields.EXCLUDED_COLUMNS;
import static com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields.METRIC_NAMES_FIELD;
import static com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields.METRIC_NAMES_SOURCE;
import static com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields.REF_DESCRIPTORS;
import static com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields.SUITE_SNAPSHOT_COLUMN;
import static com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields.SUITE_TYPE_FIELD;
import static com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields.subField;

import com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields.RefDescriptor;
import com.epam.aidial.evaluation.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.query.service.dto.QuerySchemaFieldDto;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Schema provider for the simple {@code test_suite_runs} entity. The base schema is
 * {@link JooqTableSchemaResolver#resolve} over the generated {@code TEST_SUITE_RUNS} table, minus
 * {@link TestSuiteRunQueryFields#EXCLUDED_COLUMNS} (heavy/opaque JSONB payloads not exposed as
 * fields), plus the {@code suite_snapshot}-backed {@code suite_type}/{@code deployment_ref::*}/
 * {@code mcp_deployment_ref::*} virtual fields and {@code metric_names}. Consumes the same
 * {@link TestSuiteRunQueryFields} constants as {@code PostgresTestSuiteRunEntityResolver} so the
 * published schema and the executable field set cannot drift apart.
 */
@Component
@LogExecution
public class TestSuiteRunsSchemaProvider implements QueryableEntitySchemaProvider {

    private static final QueryEntityDto DESCRIPTOR = new QueryEntityDto(TestSuiteRunQueryFields.ENTITY, false, null);

    private final List<QuerySchemaFieldDto> baseSchema;

    public TestSuiteRunsSchemaProvider(JooqTableSchemaResolver schemaResolver) {
        final List<QuerySchemaFieldDto> schema = new ArrayList<>();
        for (final QuerySchemaFieldDto field : schemaResolver.resolve(TEST_SUITE_RUNS)) {
            if (!EXCLUDED_COLUMNS.contains(field.name())) {
                schema.add(field);
            }
        }
        schema.add(new QuerySchemaFieldDto(SUITE_TYPE_FIELD, QueryFieldType.STRING, SUITE_SNAPSHOT_COLUMN));
        for (final RefDescriptor ref : REF_DESCRIPTORS) {
            for (final String subKey : ref.subKeys()) {
                schema.add(new QuerySchemaFieldDto(
                        subField(ref.fieldPrefix(), subKey), QueryFieldType.STRING, SUITE_SNAPSHOT_COLUMN));
            }
        }
        schema.add(new QuerySchemaFieldDto(METRIC_NAMES_FIELD, QueryFieldType.ARRAY, METRIC_NAMES_SOURCE));
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
