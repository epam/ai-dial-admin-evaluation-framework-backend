package com.epam.aidial.evaluation.data.db.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.TestSuiteMetricDefinitionsRecord;
import com.epam.aidial.evaluation.data.db.model.TestSuiteMetricDefinition;
import java.util.UUID;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class TestSuiteMetricDefinitionRecordMapper {

    public TestSuiteMetricDefinition map(TestSuiteMetricDefinitionsRecord r) {
        return TestSuiteMetricDefinition.builder()
                .id(UUID.fromString(r.getId()))
                .testSuiteId(UUID.fromString(r.getTestSuiteId()))
                .metricDeclarationId(UUID.fromString(r.getMetricDeclarationId()))
                .metricDeclarationVersionId(UUID.fromString(r.getMetricDeclarationVersionId()))
                .name(r.getName())
                .configBindings(toJsonString(r.getConfigBindings()))
                .inputBindings(toJsonString(r.getInputBindings()))
                .enabled(r.getIsEnabled())
                .valid(r.getIsValid())
                .validationWarnings(toJsonString(r.getValidationWarnings()))
                .createdAt(r.getCreatedAtMs())
                .updatedAt(r.getUpdatedAtMs())
                .build();
    }

    private static String toJsonString(JSONB jsonb) {
        return jsonb != null ? jsonb.data() : null;
    }
}
