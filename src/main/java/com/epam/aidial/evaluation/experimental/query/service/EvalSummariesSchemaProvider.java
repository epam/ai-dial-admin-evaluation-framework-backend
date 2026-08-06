package com.epam.aidial.evaluation.experimental.query.service;

import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.COLUMN_SEPARATOR;
import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.DATA_COLUMN_PREFIX;
import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.METRIC_COLUMN_PREFIX;
import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.METRIC_INFO_COLUMN_PREFIX;
import static com.epam.aidial.evaluation.constants.EvalSummaryExportColumnConstants.RESPONSE_COLUMN_PREFIX;
import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;

import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryEntityDto;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.experimental.query.service.dto.QuerySchemaFieldDto;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.OutputSchemaFieldExtractor;
import com.epam.aidial.evaluation.service.domain.ResponseColumnUnionResolver;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunService;
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
 * families derived from the <strong>run snapshot</strong> of a particular test suite run — so the
 * advertised fields match what that run actually produced, even after the suite, dataset, or metric
 * definitions change.
 *
 * <p>Resolution is keyed by run: callers pass {@code test_suite_run_id} (preferred) to target a run
 * directly, or {@code test_suite_id} to target the suite's latest run. The schema is then derived
 * from that run's {@link SuiteSnapshotDto} ({@code data::<field>} from the snapshot's test-case
 * schema, {@code response::<column>} from the suite-wide response-column union — the snapshot's own
 * {@code responseColumns} followed by each {@code additionalRequests[i].responseColumns}, via
 * {@link ResponseColumnUnionResolver}) and the run's analytics
 * {@link RunMetricSnapshot} rows for the latest computation ({@code metric::<name>::<field>}, always
 * numeric — metric values are numbers by nature; {@code metricInfo::<name>}, an opaque object holding
 * any non-numeric per-metric info/error, split by metric name only). These mirror the CSV export
 * manifest's column families. Runs without a snapshot (legacy null snapshot) are rejected with a
 * {@link ValidationException}.
 */
@Slf4j
@Component
@LogExecution
public class EvalSummariesSchemaProvider implements QueryableEntitySchemaProvider {

    static final String ENTITY_NAME = "eval_summaries";
    static final String RUN_ID_FIELD = "test_suite_run_id";
    static final String SUITE_ID_FIELD = "test_suite_id";

    static final String TEST_CASE_DATA_FIELD = "test_case_data";
    static final String EXTRACTED_COLUMNS_FIELD = "extracted_columns";
    static final String METRIC_VALUES_FIELD = "metric_values";
    static final String METRIC_INFOS_FIELD = "metric_infos";

    private static final QueryEntityDto DESCRIPTOR = new QueryEntityDto(ENTITY_NAME, true, RUN_ID_FIELD);

    /** JSONB fields the detailed schema replaces with per-instance flattened families. */
    private static final Set<String> FLATTENABLE_JSONB_FIELDS =
            Set.of(TEST_CASE_DATA_FIELD, EXTRACTED_COLUMNS_FIELD, METRIC_VALUES_FIELD, METRIC_INFOS_FIELD);

    private final TestSuiteRunService testSuiteRunService;
    private final RunMetricSnapshotRepository runMetricSnapshotRepository;
    private final OutputSchemaFieldExtractor outputSchemaFieldExtractor;
    private final SchemaFieldTypeMapper schemaFieldTypeMapper;
    private final ResponseColumnUnionResolver responseColumnUnionResolver;
    private final List<QuerySchemaFieldDto> baseSchema;

    public EvalSummariesSchemaProvider(
            TestSuiteRunService testSuiteRunService,
            RunMetricSnapshotRepository runMetricSnapshotRepository,
            OutputSchemaFieldExtractor outputSchemaFieldExtractor,
            SchemaFieldTypeMapper schemaFieldTypeMapper,
            ResponseColumnUnionResolver responseColumnUnionResolver,
            JooqTableSchemaResolver schemaResolver) {
        this.testSuiteRunService = testSuiteRunService;
        this.runMetricSnapshotRepository = runMetricSnapshotRepository;
        this.outputSchemaFieldExtractor = outputSchemaFieldExtractor;
        this.schemaFieldTypeMapper = schemaFieldTypeMapper;
        this.responseColumnUnionResolver = responseColumnUnionResolver;
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
        final TestSuiteRunResponseDto run = resolveRun(params);
        final SuiteSnapshotDto snapshot = requireSnapshot(run);
        final List<RunMetricSnapshot> metricSnapshots = latestComputationMetricSnapshots(run.getId());

        final List<QuerySchemaFieldDto> fields = new ArrayList<>(baseSchema.stream()
                .filter(field -> !FLATTENABLE_JSONB_FIELDS.contains(field.name()))
                .toList());
        fields.addAll(dataFields(snapshot));
        fields.addAll(responseFields(snapshot));
        fields.addAll(metricFields(metricSnapshots));
        return List.copyOf(fields);
    }

    /**
     * Resolves the run whose snapshot defines the detailed schema. {@code test_suite_run_id} targets
     * a run directly; failing that, {@code test_suite_id} targets the suite's latest run.
     */
    private TestSuiteRunResponseDto resolveRun(Map<String, String> params) {
        final String runId = params.get(RUN_ID_FIELD);
        if (runId != null) {
            return testSuiteRunService.getRun(parseUuid(runId, RUN_ID_FIELD));
        }
        final String suiteId = params.get(SUITE_ID_FIELD);
        if (suiteId != null) {
            return testSuiteRunService.getLatestRun(parseUuid(suiteId, SUITE_ID_FIELD));
        }
        throw new ValidationException("Detailed schema for entity '" + ENTITY_NAME + "' requires '" + RUN_ID_FIELD
                + "' (preferred) or '" + SUITE_ID_FIELD + "'");
    }

    private SuiteSnapshotDto requireSnapshot(TestSuiteRunResponseDto run) {
        final SuiteSnapshotDto snapshot = run.getSuiteSnapshot();
        if (snapshot == null) {
            throw new ValidationException("Run " + run.getId() + " has no suite snapshot; the detailed schema can "
                    + "only be derived from runs created under the snapshot model");
        }
        return snapshot;
    }

    /** The metric snapshots of the run's latest computation; empty when the run has none. */
    private List<RunMetricSnapshot> latestComputationMetricSnapshots(UUID runId) {
        return runMetricSnapshotRepository
                .findLatestComputationId(runId)
                .map(computationId -> runMetricSnapshotRepository.findByRunIdAndComputationId(runId, computationId))
                .orElseGet(List::of);
    }

    private List<QuerySchemaFieldDto> dataFields(SuiteSnapshotDto snapshot) {
        if (snapshot.getTestCaseSchema() == null) {
            return List.of();
        }
        return snapshot.getTestCaseSchema().stream()
                .map(field -> new QuerySchemaFieldDto(
                        DATA_COLUMN_PREFIX + field.getName(),
                        schemaFieldTypeMapper.map(field.getType()),
                        TEST_CASE_DATA_FIELD))
                .toList();
    }

    private List<QuerySchemaFieldDto> responseFields(SuiteSnapshotDto snapshot) {
        final List<ResponseColumnDefinitionDto> responseColumns = responseColumnUnionResolver.unionFrom(snapshot);
        if (responseColumns.isEmpty()) {
            return List.of();
        }
        return responseColumns.stream()
                .map(column -> new QuerySchemaFieldDto(
                        RESPONSE_COLUMN_PREFIX + column.getName(),
                        schemaFieldTypeMapper.map(column.getType()),
                        EXTRACTED_COLUMNS_FIELD))
                .toList();
    }

    private List<QuerySchemaFieldDto> metricFields(List<RunMetricSnapshot> metricSnapshots) {
        final List<QuerySchemaFieldDto> fields = new ArrayList<>();
        for (RunMetricSnapshot snapshot : metricSnapshots) {
            // Every metric output field is a number (the metric's value); non-numeric output lands
            // in metricInfos, which we expose per metric name only, not per field.
            outputSchemaFieldExtractor.extractFieldNames(snapshot.getOutputSchema()).stream()
                    .map(outputField -> new QuerySchemaFieldDto(
                            METRIC_COLUMN_PREFIX + snapshot.getTsmdName() + COLUMN_SEPARATOR + outputField,
                            QueryFieldType.DECIMAL,
                            METRIC_VALUES_FIELD))
                    .forEach(fields::add);
            fields.add(new QuerySchemaFieldDto(
                    METRIC_INFO_COLUMN_PREFIX + snapshot.getTsmdName(), QueryFieldType.OBJECT, METRIC_INFOS_FIELD));
        }
        return fields;
    }

    private static UUID parseUuid(String value, String fieldName) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            log.warn("Rejecting malformed '{}' value '{}': {}", fieldName, value, e.getMessage(), e);
            throw new ValidationException(
                    "Value of '" + fieldName + "' for entity '" + ENTITY_NAME + "' must be a UUID");
        }
    }
}
