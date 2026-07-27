package com.epam.aidial.evaluation.data.db.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.TestCaseRunInputsRecord;
import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import java.util.UUID;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class TestCaseRunInputRecordMapper {

    public TestCaseRunInput map(TestCaseRunInputsRecord r) {
        return TestCaseRunInput.builder()
                .runId(UUID.fromString(r.getRunId()))
                .position(r.getPosition())
                .testCaseId(UUID.fromString(r.getTestCaseId()))
                .testCaseName(r.getTestCaseName())
                .testCaseData(toJsonString(r.getTestCaseData()))
                .multiTurnData(toJsonString(r.getMultiTurnData()))
                .requestTemplateOverride(toJsonString(r.getRequestTemplateOverride()))
                .inputBindingsOverride(toJsonString(r.getInputBindingsOverride()))
                .build();
    }

    private static String toJsonString(JSONB jsonb) {
        return jsonb != null ? jsonb.data() : null;
    }
}
