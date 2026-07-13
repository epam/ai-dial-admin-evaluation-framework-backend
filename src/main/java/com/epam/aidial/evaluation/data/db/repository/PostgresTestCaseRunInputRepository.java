package com.epam.aidial.evaluation.data.db.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_CASE_RUN_INPUTS;
import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITE_RUNS;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.mapper.TestCaseRunInputRecordMapper;
import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Query;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresTestCaseRunInputRepository implements TestCaseRunInputRepository {

    @Qualifier("metaDsl")
    private final DSLContext dsl;

    private final TestCaseRunInputRecordMapper recordMapper;
    private final Clock clock;

    @Override
    public void insertBatch(List<TestCaseRunInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return;
        }
        List<Query> queries = inputs.stream()
                .map(input -> (Query) dsl.insertInto(TEST_CASE_RUN_INPUTS)
                        .set(TEST_CASE_RUN_INPUTS.RUN_ID, input.getRunId().toString())
                        .set(TEST_CASE_RUN_INPUTS.POSITION, input.getPosition())
                        .set(
                                TEST_CASE_RUN_INPUTS.TEST_CASE_ID,
                                input.getTestCaseId().toString())
                        .set(TEST_CASE_RUN_INPUTS.TEST_CASE_NAME, input.getTestCaseName())
                        .set(TEST_CASE_RUN_INPUTS.TEST_CASE_DATA, toJsonb(input.getTestCaseData()))
                        .set(
                                TEST_CASE_RUN_INPUTS.REQUEST_TEMPLATE_OVERRIDE,
                                toJsonb(input.getRequestTemplateOverride()))
                        .set(TEST_CASE_RUN_INPUTS.INPUT_BINDINGS_OVERRIDE, toJsonb(input.getInputBindingsOverride()))
                        .set(
                                TEST_CASE_RUN_INPUTS.CONVERSATION_ID,
                                input.getConversationId() != null
                                        ? input.getConversationId().toString()
                                        : null)
                        .set(TEST_CASE_RUN_INPUTS.TOTAL_TURNS, input.getTotalTurns())
                        .set(TEST_CASE_RUN_INPUTS.TURNS, toJsonb(input.getTurns()))
                        .set(TEST_CASE_RUN_INPUTS.BROKEN, input.isBroken()))
                .toList();
        dsl.batch(queries).execute();
    }

    @Override
    public List<TestCaseRunInput> findByRunId(UUID runId, int offset, int limit) {
        return dsl.selectFrom(TEST_CASE_RUN_INPUTS)
                .where(TEST_CASE_RUN_INPUTS.RUN_ID.eq(runId.toString()))
                .orderBy(TEST_CASE_RUN_INPUTS.POSITION.asc())
                .limit(limit)
                .offset(offset)
                .fetch(recordMapper::map);
    }

    @Override
    public long countByRunId(UUID runId) {
        Long count = dsl.selectCount()
                .from(TEST_CASE_RUN_INPUTS)
                .where(TEST_CASE_RUN_INPUTS.RUN_ID.eq(runId.toString()))
                .fetchOne(0, Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public boolean existsByRunId(UUID runId) {
        return dsl.fetchExists(TEST_CASE_RUN_INPUTS, TEST_CASE_RUN_INPUTS.RUN_ID.eq(runId.toString()));
    }

    @Override
    public int deleteByRunIdsInTerminalStateOlderThan(Duration retention) {
        long cutoffMs = clock.millis() - retention.toMillis();
        return dsl.deleteFrom(TEST_CASE_RUN_INPUTS)
                .where(TEST_CASE_RUN_INPUTS.RUN_ID.in(dsl.select(TEST_SUITE_RUNS.ID)
                        .from(TEST_SUITE_RUNS)
                        .where(TEST_SUITE_RUNS
                                .STATUS
                                .in("COMPLETED", "FAILED")
                                .and(TEST_SUITE_RUNS.UPDATED_AT_MS.lt(cutoffMs)))))
                .execute();
    }

    public void deleteByRunId(UUID runId) {
        dsl.deleteFrom(TEST_CASE_RUN_INPUTS)
                .where(TEST_CASE_RUN_INPUTS.RUN_ID.eq(runId.toString()))
                .execute();
    }

    private static JSONB toJsonb(String json) {
        return json != null ? JSONB.valueOf(json) : null;
    }
}
