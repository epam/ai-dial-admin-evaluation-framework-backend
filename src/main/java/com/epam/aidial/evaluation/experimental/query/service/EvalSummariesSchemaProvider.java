package com.epam.aidial.evaluation.experimental.query.service;

import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.COLUMN_SEPARATOR;
import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.DATA_COLUMN_PREFIX;
import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.METRIC_COLUMN_PREFIX;
import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.METRIC_INFO_COLUMN_PREFIX;
import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.RESPONSE_COLUMN_PREFIX;
import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.experimental.query.service.dto.QuerySchemaFieldDto;
import com.epam.aidial.evaluation.service.domain.DatasetSchemaProvider;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import com.epam.aidial.evaluation.service.domain.TestSuiteMetricDefinitionService;
import com.epam.aidial.evaluation.service.domain.TestSuiteService;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Schema provider for the complex {@code eval_summaries} entity (analytics table
 * {@code test_case_eval_summaries}). The base schema is derived once from the generated jOOQ table,
 * listing JSONB fields as-is; the detailed schema replaces the flattenable JSONB fields with
 * families derived from the <strong>current</strong> state of the test suite identified by
 * {@code testSuiteId} (not from a run snapshot): {@code data:<field>} from the bound dataset's test
 * case schema, {@code response:<column>} from the suite's response columns,
 * {@code metric:<name>:<field>} (always numeric — metric values are numbers by nature) from the
 * enabled and valid metric definitions' output schemas, and {@code metricInfo:<name>} (an opaque
 * object holding any non-numeric per-metric info/error) split by metric name only. These reuse the
 * CSV export manifest's column families.
 */
@Slf4j
@Component
@LogExecution
public class EvalSummariesSchemaProvider implements QueryableEntitySchemaProvider {

    static final String ENTITY_NAME = "eval_summaries";
    static final String SCHEMA_ID_FIELD = "test_suite_id";

    static final String TEST_CASE_DATA_FIELD = "test_case_data";
    static final String EXTRACTED_COLUMNS_FIELD = "extracted_columns";
    static final String METRIC_VALUES_FIELD = "metric_values";
    static final String METRIC_INFOS_FIELD = "metric_infos";

    private static final QueryEntityDto DESCRIPTOR = new QueryEntityDto(ENTITY_NAME, true, SCHEMA_ID_FIELD);

    /** JSONB fields the detailed schema replaces with per-instance flattened families. */
    private static final Set<String> FLATTENABLE_JSONB_FIELDS =
            Set.of(TEST_CASE_DATA_FIELD, EXTRACTED_COLUMNS_FIELD, METRIC_VALUES_FIELD, METRIC_INFOS_FIELD);

    private final TestSuiteService testSuiteService;
    private final DatasetSchemaProvider datasetSchemaProvider;
    private final TestSuiteMetricDefinitionService testSuiteMetricDefinitionService;
    private final OutputSchemaFieldExtractor outputSchemaFieldExtractor;
    private final List<QuerySchemaFieldDto> baseSchema;

    public EvalSummariesSchemaProvider(
            TestSuiteService testSuiteService,
            DatasetSchemaProvider datasetSchemaProvider,
            TestSuiteMetricDefinitionService testSuiteMetricDefinitionService,
            OutputSchemaFieldExtractor outputSchemaFieldExtractor,
            JooqTableSchemaResolver schemaResolver) {
        this.testSuiteService = testSuiteService;
        this.datasetSchemaProvider = datasetSchemaProvider;
        this.testSuiteMetricDefinitionService = testSuiteMetricDefinitionService;
        this.outputSchemaFieldExtractor = outputSchemaFieldExtractor;
        this.baseSchema = schemaResolver.resolve(TEST_CASE_EVAL_SUMMARIES);
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
        /*
        todo: branching logic: if params contains testSuiteRunId param -> fetch schema from specified test suite run
              if params contains testSuiteid -> fetch latest run by test suite id and extract schema using the same logic
              as above.
        */

        final TestSuiteResponseDto suite = testSuiteService.getById(parseSuiteId(params.get(SCHEMA_ID_FIELD)));

        final List<QuerySchemaFieldDto> fields = new ArrayList<>(baseSchema.stream()
                .filter(field -> !FLATTENABLE_JSONB_FIELDS.contains(field.name()))
                .toList());
        fields.addAll(dataFields(suite));
        fields.addAll(responseFields(suite));
        fields.addAll(metricFields(suite));
        return List.copyOf(fields);
    }

    private List<QuerySchemaFieldDto> dataFields(TestSuiteResponseDto suite) {
        if (suite.getDatasetId() == null) {
            return List.of();
        }
        return datasetSchemaProvider.getSchema(suite.getDatasetId()).stream()
                .map(field -> new QuerySchemaFieldDto(
                        DATA_COLUMN_PREFIX + field.getName(),
                        mapSchemaFieldType(field.getType()),
                        TEST_CASE_DATA_FIELD))
                .toList();
    }

    private List<QuerySchemaFieldDto> responseFields(TestSuiteResponseDto suite) {
        final List<ResponseColumnDefinitionDto> responseColumns = suite.getResponseColumns();
        if (responseColumns == null) {
            return List.of();
        }
        return responseColumns.stream()
                .map(column -> new QuerySchemaFieldDto(
                        RESPONSE_COLUMN_PREFIX + column.getName(),
                        mapSchemaFieldType(column.getType()),
                        EXTRACTED_COLUMNS_FIELD))
                .toList();
    }

    private List<QuerySchemaFieldDto> metricFields(TestSuiteResponseDto suite) {
        final List<AggregatedMetricDefinition> definitions =
                testSuiteMetricDefinitionService.findAllEnabledAndValidAggregatedByTestSuiteId(suite.getId());
        final List<QuerySchemaFieldDto> fields = new ArrayList<>();
        for (AggregatedMetricDefinition definition : definitions) {
            // Every metric output field is a number (the metric's value); non-numeric output lands
            // in metricInfos, which we expose per metric name only, not per field.
            outputSchemaFieldExtractor.extractFieldNames(definition.getVersionOutputSchema()).stream()
                    .map(outputField -> new QuerySchemaFieldDto(
                            METRIC_COLUMN_PREFIX + definition.getName() + COLUMN_SEPARATOR + outputField,
                            QueryFieldType.DECIMAL,
                            METRIC_VALUES_FIELD))
                    .forEach(fields::add);
            fields.add(new QuerySchemaFieldDto(
                    METRIC_INFO_COLUMN_PREFIX + definition.getName(), QueryFieldType.OBJECT, METRIC_INFOS_FIELD));
        }
        return fields;
    }

    private static UUID parseSuiteId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            log.warn("Rejecting malformed test suite id '{}': {}", id, e.getMessage(), e);
            throw new ValidationException("Schema id for entity '" + ENTITY_NAME + "' must be a test suite UUID");
        }
    }

    /** Maps a dataset/response-column declared type onto the DSL-aligned field type vocabulary. */
    private static QueryFieldType mapSchemaFieldType(SchemaFieldType type) {
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
}
