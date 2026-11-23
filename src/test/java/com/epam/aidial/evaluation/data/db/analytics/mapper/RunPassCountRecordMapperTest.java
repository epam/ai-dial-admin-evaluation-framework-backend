package com.epam.aidial.evaluation.data.db.analytics.mapper;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.RUN_PASS_COUNTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCount;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunPassCountRecordMapperTest {
    private final DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
    private final RunPassCountRecordMapper mapper = new RunPassCountRecordMapper();
    private final Table<?> projected =
            dsl.select(RUN_PASS_COUNTS.fields()).from(RUN_PASS_COUNTS).asTable("latest");

    @Test
    @DisplayName("maps all values from an aliased pass-count projection")
    void mapsAliasedProjection() {
        final Record record = record("00000000-0000-0000-0000-000000000003");

        assertThat(mapper.map(record, projected))
                .isEqualTo(RunPassCount.builder()
                        .testSuiteRunId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                        .testSuiteId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
                        .computationId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
                        .runCreatedAtMs(1234L)
                        .passedCount(4)
                        .totalCount(7)
                        .computedAtMs(5678L)
                        .build());
    }

    @Test
    @DisplayName("preserves a null computation in an aliased pass-count projection")
    void preservesNullComputation() {
        assertThat(mapper.map(record(null), projected).getComputationId()).isNull();
    }

    private Record record(String computation) {
        final Record record = dsl.newRecord(projected.fields());
        record.set(projected.field(RUN_PASS_COUNTS.TEST_SUITE_RUN_ID), "00000000-0000-0000-0000-000000000001");
        record.set(projected.field(RUN_PASS_COUNTS.TEST_SUITE_ID), "00000000-0000-0000-0000-000000000002");
        record.set(projected.field(RUN_PASS_COUNTS.COMPUTATION_ID), computation);
        record.set(projected.field(RUN_PASS_COUNTS.RUN_CREATED_AT_MS), 1234L);
        record.set(projected.field(RUN_PASS_COUNTS.PASSED_COUNT), 4);
        record.set(projected.field(RUN_PASS_COUNTS.TOTAL_COUNT), 7);
        record.set(projected.field(RUN_PASS_COUNTS.COMPUTED_AT_MS), 5678L);
        return record;
    }
}
