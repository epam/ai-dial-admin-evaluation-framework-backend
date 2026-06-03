package com.epam.aidial.evaluation.data.db.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.TestCasesRecord;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import java.util.UUID;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class TestCaseRecordMapper {

    public TestCase map(TestCasesRecord r) {
        String validationWarnings = toJsonString(r.getValidationWarnings());
        return TestCase.builder()
                .id(UUID.fromString(r.getId()))
                .datasetId(UUID.fromString(r.getDatasetId()))
                .testCaseName(r.getTestCaseName())
                .data(toJsonString(r.getData()))
                .valid(r.getIsValid())
                .validationWarnings(validationWarnings != null ? validationWarnings : "[]")
                .createdAt(r.getCreatedAtMs())
                .updatedAt(r.getUpdatedAtMs())
                .build();
    }

    private static String toJsonString(JSONB jsonb) {
        return jsonb != null ? jsonb.data() : null;
    }
}
