package com.epam.aidial.evaluation.experimental.query.service;

import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.DATA_COLUMN_PREFIX;
import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_CASES;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.repository.sql.json.JsonPathAccessor;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.service.domain.DatasetSchemaProvider;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code test_cases} field bindings for a specific dataset: the base {@code TEST_CASES}
 * columns (via {@link JooqTableSchemaResolver}) plus one flattened {@code data::<field>} binding per
 * dataset schema field, each carrying the JSONB-path {@link Field} it denotes and its
 * {@link QueryFieldType}. Scalar fields extract as text ({@code data ->> 'field'}); array/object
 * fields keep the raw JSONB ({@code data -> 'field'}) so array-typed fields can be filtered by JSONB
 * containment. This is the single source of the flattened typing, reused by the execute path (via the
 * precomputed-bindings executor overload), run selection, and write-time validation.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class TestCaseFieldBindingsBuilder {

    private final JooqTableSchemaResolver schemaResolver;
    private final JsonPathAccessor jsonPathAccessor;
    private final DatasetSchemaProvider datasetSchemaProvider;
    private final SchemaFieldTypeMapper schemaFieldTypeMapper;

    /** Builds bindings for the dataset's current test-case schema (loads it via {@link DatasetSchemaProvider}). */
    public Map<String, QueryFieldBinding> build(UUID datasetId) {
        return build(datasetSchemaProvider.getSchema(datasetId));
    }

    /**
     * Builds bindings for the dataset's current schema with the flattened {@code data::<field>} paths
     * pointing at a supplied JSONB source instead of {@code TEST_CASES.DATA}. Used to re-point the filter
     * at a per-turn element ({@code elem}) inside the ALL-turns-match lateral (see
     * {@code QueryDslRunnableTestCaseSelector}); base {@code TEST_CASES} columns remain correlated to the
     * outer row.
     */
    public Map<String, QueryFieldBinding> build(UUID datasetId, Field<JSONB> dataSource) {
        return build(datasetSchemaProvider.getSchema(datasetId), dataSource);
    }

    /** Builds bindings from an already-resolved test-case schema against {@code TEST_CASES.DATA}. */
    public Map<String, QueryFieldBinding> build(List<FieldDefinitionDto> schema) {
        return build(schema, TEST_CASES.DATA);
    }

    /** Builds bindings from a resolved schema with {@code data::<field>} paths against {@code dataSource}. */
    public Map<String, QueryFieldBinding> build(List<FieldDefinitionDto> schema, Field<JSONB> dataSource) {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>(schemaResolver.bindings(TEST_CASES));
        for (final FieldDefinitionDto field : schema) {
            final String name = DATA_COLUMN_PREFIX + field.getName();
            final QueryFieldType type = schemaFieldTypeMapper.map(field.getType());
            final Field<?> path = type.isJsonb()
                    ? jsonPathAccessor.jsonbAt(dataSource, DSL.val(field.getName()))
                    : jsonPathAccessor.jsonbAtAsText(dataSource, DSL.val(field.getName()));
            bindings.put(name, new QueryFieldBinding(name, path, type));
        }
        return Collections.unmodifiableMap(bindings);
    }
}
