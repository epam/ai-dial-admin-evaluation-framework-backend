package com.epam.aidial.evaluation.data.db.repository;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITE_SCHEDULES;

import com.epam.aidial.evaluation.data.db.mapper.TestSuiteScheduleRecordMapper;
import com.epam.aidial.evaluation.data.db.model.TestSuiteSchedule;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.sql.PageRequestSqlBuilder;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@LogExecution
@RequiredArgsConstructor
@ConditionalOnProperty(name = "datasource.meta.vendor", havingValue = "POSTGRES")
public class PostgresTestSuiteScheduleRepository implements TestSuiteScheduleRepository {

    @Qualifier("metaDsl")
    private final DSLContext dsl;

    private final TestSuiteScheduleRecordMapper recordMapper;

    @Override
    public TestSuiteSchedule save(TestSuiteSchedule schedule) {
        dsl.insertInto(TEST_SUITE_SCHEDULES)
                .set(TEST_SUITE_SCHEDULES.SCHEDULE_ID, schedule.getScheduleId())
                .set(
                        TEST_SUITE_SCHEDULES.TEST_SUITE_ID,
                        schedule.getTestSuiteId().toString())
                .set(TEST_SUITE_SCHEDULES.CREATED_BY, schedule.getCreatedBy())
                .set(TEST_SUITE_SCHEDULES.CREATED_AT, schedule.getCreatedAt())
                .set(TEST_SUITE_SCHEDULES.SCHEDULE_REF, schedule.getScheduleRef())
                .execute();
        return schedule;
    }

    @Override
    public Optional<TestSuiteSchedule> findById(String scheduleId) {
        return dsl.selectFrom(TEST_SUITE_SCHEDULES)
                .where(TEST_SUITE_SCHEDULES.SCHEDULE_ID.eq(scheduleId))
                .fetchOptional(recordMapper::map);
    }

    @Override
    public Optional<TestSuiteSchedule> findByRef(String scheduleRef) {
        if (scheduleRef == null || scheduleRef.isBlank()) {
            return Optional.empty();
        }
        return dsl.selectFrom(TEST_SUITE_SCHEDULES)
                .where(TEST_SUITE_SCHEDULES.SCHEDULE_REF.eq(scheduleRef))
                .fetchOptional(recordMapper::map);
    }

    @Override
    public boolean updateRef(String scheduleId, String scheduleRef) {
        return dsl.update(TEST_SUITE_SCHEDULES)
                        .set(TEST_SUITE_SCHEDULES.SCHEDULE_REF, scheduleRef)
                        .where(TEST_SUITE_SCHEDULES.SCHEDULE_ID.eq(scheduleId))
                        .execute()
                > 0;
    }

    @Override
    public Page<TestSuiteSchedule> findPageByTestSuiteId(UUID testSuiteId, PageRequest pageRequest) {
        final String suiteId = testSuiteId.toString();
        final long total = dsl.fetchCount(
                dsl.selectFrom(TEST_SUITE_SCHEDULES).where(TEST_SUITE_SCHEDULES.TEST_SUITE_ID.eq(suiteId)));
        final List<TestSuiteSchedule> content = dsl.selectFrom(TEST_SUITE_SCHEDULES)
                .where(TEST_SUITE_SCHEDULES.TEST_SUITE_ID.eq(suiteId))
                .orderBy(TEST_SUITE_SCHEDULES.CREATED_AT.desc(), TEST_SUITE_SCHEDULES.SCHEDULE_ID.asc())
                .limit(PageRequestSqlBuilder.limit(pageRequest))
                .offset(PageRequestSqlBuilder.offset(pageRequest))
                .fetch(recordMapper::map);
        return Page.of(content, pageRequest, total);
    }

    @Override
    public boolean deleteById(String scheduleId) {
        return dsl.deleteFrom(TEST_SUITE_SCHEDULES)
                        .where(TEST_SUITE_SCHEDULES.SCHEDULE_ID.eq(scheduleId))
                        .execute()
                > 0;
    }
}
