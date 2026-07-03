package com.epam.aidial.evaluation.experimental.query.service;

import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.DATA_COLUMN_PREFIX;
import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_CASES;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.experimental.query.service.dto.QuerySchemaFieldDto;
import com.epam.aidial.evaluation.service.domain.DatasetSchemaProvider;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Schema provider for the complex {@code test_cases} entity (meta table {@code test_cases}). The base
 * schema is derived once from the generated jOOQ table, listing the JSONB {@code data} field as-is;
 * the detailed schema replaces {@code data} with {@code data::<field>} families derived from a
 * particular dataset's test-case schema, so the advertised fields match the dataset the client is
 * querying. Resolution is keyed by {@code dataset_id}.
 */
@Slf4j
@Component
@LogExecution
public class TestCasesSchemaProvider implements QueryableEntitySchemaProvider {

    static final String ENTITY_NAME = "test_cases";
    static final String DATASET_ID_FIELD = "dataset_id";
    static final String DATA_FIELD = "data";

    private static final QueryEntityDto DESCRIPTOR = new QueryEntityDto(ENTITY_NAME, true, DATASET_ID_FIELD);

    private final DatasetSchemaProvider datasetSchemaProvider;
    private final SchemaFieldTypeMapper schemaFieldTypeMapper;
    private final List<QuerySchemaFieldDto> baseSchema;

    public TestCasesSchemaProvider(
            DatasetSchemaProvider datasetSchemaProvider,
            SchemaFieldTypeMapper schemaFieldTypeMapper,
            JooqTableSchemaResolver schemaResolver) {
        this.datasetSchemaProvider = datasetSchemaProvider;
        this.schemaFieldTypeMapper = schemaFieldTypeMapper;
        this.baseSchema = schemaResolver.resolve(TEST_CASES);
    }

    @Override
    public QueryEntityDto descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<QuerySchemaFieldDto> baseSchema() {
        return baseSchema;
    }

    @Override
    public List<QuerySchemaFieldDto> detailedSchema(Map<String, String> params) {
        final UUID datasetId = parseDatasetId(params.get(DATASET_ID_FIELD));
        final List<FieldDefinitionDto> schema = datasetSchemaProvider.getSchema(datasetId);
        final List<QuerySchemaFieldDto> fields = new ArrayList<>(baseSchema.stream()
                .filter(field -> !DATA_FIELD.equals(field.name()))
                .toList());
        for (final FieldDefinitionDto field : schema) {
            fields.add(new QuerySchemaFieldDto(
                    DATA_COLUMN_PREFIX + field.getName(), schemaFieldTypeMapper.map(field.getType()), DATA_FIELD));
        }
        return List.copyOf(fields);
    }

    private static UUID parseDatasetId(String value) {
        if (value == null) {
            throw new ValidationException(
                    "Detailed schema for entity '" + ENTITY_NAME + "' requires '" + DATASET_ID_FIELD + "'");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            log.warn("Rejecting malformed '{}' value '{}': {}", DATASET_ID_FIELD, value, e.getMessage(), e);
            throw new ValidationException(
                    "Value of '" + DATASET_ID_FIELD + "' for entity '" + ENTITY_NAME + "' must be a UUID");
        }
    }
}
