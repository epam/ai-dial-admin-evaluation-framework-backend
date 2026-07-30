package com.epam.aidial.evaluation.data.db.mapper;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITE_RUNS;

import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.TestSuiteRunsRecord;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.UUID;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class TestSuiteRunRecordMapper {

    /**
     * Maps a typed {@link TestSuiteRunsRecord} — used when all columns including
     * {@code suite_snapshot} are fetched (e.g. findById).
     */
    public TestSuiteRun map(TestSuiteRunsRecord r) {
        return TestSuiteRun.builder()
                .id(UUID.fromString(r.getId()))
                .testSuiteId(UUID.fromString(r.getTestSuiteId()))
                .testRunName(r.getTestRunName())
                .status(r.getStatus())
                .runConfig(toJsonString(r.getRunConfig()))
                .numberOfTestCases(r.getNumberOfTestCases())
                .startedAt(r.getStartedAtMs())
                .completedAt(r.getCompletedAtMs())
                .errorMessage(r.getErrorMessage())
                .errorDetails(toJsonString(r.getErrorDetails()))
                .suiteSnapshot(toJsonString(r.getSuiteSnapshot()))
                .createdAt(r.getCreatedAtMs())
                .updatedAt(r.getUpdatedAtMs())
                .build();
    }

    /**
     * Maps a generic {@link Record} — used when {@code suite_snapshot} is excluded
     * from the query for TOAST optimization (e.g. list queries).
     * The {@code suite_snapshot} field is set to {@code null} in the result.
     */
    public TestSuiteRun mapWithoutSnapshot(Record r) {
        return TestSuiteRun.builder()
                .id(UUID.fromString(r.getValue(TEST_SUITE_RUNS.ID)))
                .testSuiteId(UUID.fromString(r.getValue(TEST_SUITE_RUNS.TEST_SUITE_ID)))
                .testRunName(r.getValue(TEST_SUITE_RUNS.TEST_RUN_NAME))
                .status(r.getValue(TEST_SUITE_RUNS.STATUS))
                .runConfig(toJsonString(r.getValue(TEST_SUITE_RUNS.RUN_CONFIG)))
                .numberOfTestCases(r.getValue(TEST_SUITE_RUNS.NUMBER_OF_TEST_CASES))
                .startedAt(r.getValue(TEST_SUITE_RUNS.STARTED_AT_MS))
                .completedAt(r.getValue(TEST_SUITE_RUNS.COMPLETED_AT_MS))
                .errorMessage(r.getValue(TEST_SUITE_RUNS.ERROR_MESSAGE))
                .errorDetails(toJsonString(r.getValue(TEST_SUITE_RUNS.ERROR_DETAILS)))
                .suiteSnapshot(null)
                .createdAt(r.getValue(TEST_SUITE_RUNS.CREATED_AT_MS))
                .updatedAt(r.getValue(TEST_SUITE_RUNS.UPDATED_AT_MS))
                .build();
    }

    private static String toJsonString(JSONB jsonb) {
        return jsonb != null ? jsonb.data() : null;
    }
}
