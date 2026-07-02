package com.epam.aidial.evaluation.experimental.query.service;

import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.DATA_COLUMN_PREFIX;
import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_CASES;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.repository.sql.json.JsonPathAccessor;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.service.domain.DatasetSchemaProvider;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
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

    /** Builds bindings for the dataset's current test-case schema (loads it via {@link DatasetSchemaProvider}). */
    public Map<String, QueryFieldBinding> build(UUID datasetId) {
        return build(datasetSchemaProvider.getSchema(datasetId));
    }

    /** Builds bindings from an already-resolved test-case schema. */
    public Map<String, QueryFieldBinding> build(List<FieldDefinitionDto> schema) {
        final Map<String, QueryFieldBinding> bindings = new LinkedHashMap<>(schemaResolver.bindings(TEST_CASES));
        final Field<JSONB> dataColumn = TEST_CASES.DATA;
        for (final FieldDefinitionDto field : schema) {
            final String name = DATA_COLUMN_PREFIX + field.getName();
            final QueryFieldType type = mapFieldType(field.getType());
            final Field<?> path = isJsonbType(type)
                    ? jsonPathAccessor.jsonbAt(dataColumn, DSL.val(field.getName()))
                    : jsonPathAccessor.jsonbAtAsText(dataColumn, DSL.val(field.getName()));
            bindings.put(name, new QueryFieldBinding(name, path, type));
        }
        return Collections.unmodifiableMap(bindings);
    }

    /** Maps a dataset-declared schema type onto the DSL-aligned query field type vocabulary. */
    public static QueryFieldType mapFieldType(SchemaFieldType type) {
        if (type == null) {
            return QueryFieldType.STRING;
        }
        return switch (type) {
            case STRING, FILE -> QueryFieldType.STRING;
            case INTEGER -> QueryFieldType.LONG;
            case NUMBER -> QueryFieldType.DECIMAL;
            case BOOLEAN -> QueryFieldType.BOOLEAN;
            case OBJECT -> QueryFieldType.OBJECT;
            case ARRAY -> QueryFieldType.ARRAY;
        };
    }

    private static boolean isJsonbType(QueryFieldType type) {
        return type == QueryFieldType.ARRAY || type == QueryFieldType.OBJECT;
    }
}
