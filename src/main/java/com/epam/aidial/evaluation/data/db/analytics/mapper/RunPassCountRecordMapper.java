package com.epam.aidial.evaluation.data.db.analytics.mapper;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.RUN_PASS_COUNTS;

import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCount;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.UUID;
import org.jooq.Record;
import org.jooq.Table;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class RunPassCountRecordMapper {
    public RunPassCount map(Record r, Table<?> projectedTable) {
        final String computation = r.get(projectedTable.field(RUN_PASS_COUNTS.COMPUTATION_ID));
        return RunPassCount.builder()
                .testSuiteRunId(UUID.fromString(r.get(projectedTable.field(RUN_PASS_COUNTS.TEST_SUITE_RUN_ID))))
                .testSuiteId(UUID.fromString(r.get(projectedTable.field(RUN_PASS_COUNTS.TEST_SUITE_ID))))
                .computationId(computation != null ? UUID.fromString(computation) : null)
                .runCreatedAtMs(r.get(projectedTable.field(RUN_PASS_COUNTS.RUN_CREATED_AT_MS)))
                .passedCount(r.get(projectedTable.field(RUN_PASS_COUNTS.PASSED_COUNT)))
                .totalCount(r.get(projectedTable.field(RUN_PASS_COUNTS.TOTAL_COUNT)))
                .computedAtMs(r.get(projectedTable.field(RUN_PASS_COUNTS.COMPUTED_AT_MS)))
                .build();
    }
}
