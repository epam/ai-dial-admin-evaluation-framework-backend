package com.epam.aidial.evaluation.experimental.query.service;

import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.DATA_COLUMN_PREFIX;
import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_CASES;

import com.epam.aidial.evaluation.data.db.repository.sql.json.JsonPathAccessor;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.DatasetSchemaProvider;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
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

    /** Builds bindings from an already-resolved test-case schema against {@code TEST_CASES.DATA}. */
    public Map<String, QueryFieldBinding> build(List<FieldDefinitionDto> schema) {
        return buildInternal(schema, field -> TEST_CASES.DATA);
    }

    /**
     * Scope-aware bindings for run selection: each field's flattened {@code data::<field>} path points at
     * {@code perTurnSource} (the per-turn element {@code elem} inside the ALL-turns-match lateral) when the
     * field is per-turn ({@code perTurn=true}), and at {@code TEST_CASES.DATA} (the outer row, constant
     * across turns) when the field is shared. Base {@code TEST_CASES} columns remain correlated to the outer
     * row. See {@code QueryDslRunnableTestCaseSelector}.
     */
    public Map<String, QueryFieldBinding> buildScoped(UUID datasetId, Field<JSONB> perTurnSource) {
        return buildScoped(datasetSchemaProvider.getSchema(datasetId), perTurnSource);
    }

    private Map<String, QueryFieldBinding> buildScoped(List<FieldDefinitionDto> schema, Field<JSONB> perTurnSource) {
        return buildInternal(
                schema, field -> Boolean.TRUE.equals(field.getPerTurn()) ? perTurnSource : TEST_CASES.DATA);
    }

    private Map<String, QueryFieldBinding> buildInternal(
            List<FieldDefinitionDto> schema, FieldSourceProvider fieldSourceProvider) {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>(schemaResolver.bindings(TEST_CASES));
        for (final FieldDefinitionDto field : schema) {
            final Field<JSONB> source = fieldSourceProvider.apply(field);
            final String name = DATA_COLUMN_PREFIX + field.getName();
            final QueryFieldType type = schemaFieldTypeMapper.map(field.getType());
            final Field<?> path = type.isJsonb()
                    ? jsonPathAccessor.jsonbAt(source, DSL.val(field.getName()))
                    : jsonPathAccessor.jsonbAtAsText(source, DSL.val(field.getName()));
            bindings.put(name, new QueryFieldBinding(name, path, type));
        }
        return Collections.unmodifiableMap(bindings);
    }

    private interface FieldSourceProvider extends Function<FieldDefinitionDto, Field<JSONB>> {}
}
