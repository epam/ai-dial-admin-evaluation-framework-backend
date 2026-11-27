package com.epam.aidial.evaluation.data.db.mapper;

import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.TestSuiteSchedulesRecord;
import com.epam.aidial.evaluation.data.db.model.TestSuiteSchedule;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class TestSuiteScheduleRecordMapper {

    public TestSuiteSchedule map(TestSuiteSchedulesRecord r) {
        return TestSuiteSchedule.builder()
                .scheduleId(r.getScheduleId())
                .testSuiteId(UUID.fromString(r.getTestSuiteId()))
                .createdBy(r.getCreatedBy())
                .createdAt(r.getCreatedAt())
                .scheduleRef(r.getScheduleRef())
                .build();
    }
}
