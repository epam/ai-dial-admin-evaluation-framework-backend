package com.epam.aidial.evaluation.data.db.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.TestSuitesRecord;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import java.util.UUID;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class TestSuiteRecordMapper {

    public TestSuite map(TestSuitesRecord r) {
        return TestSuite.builder()
                .id(UUID.fromString(r.getId()))
                .name(r.getName())
                .description(r.getDescription())
                .suiteType(SuiteType.fromValue(r.getSuiteType()))
                .datasetId(r.getDatasetId() != null ? UUID.fromString(r.getDatasetId()) : null)
                .disabledTestCaseIds(toJsonString(r.getDisabledTestCaseIds()))
                .deploymentRef(toJsonString(r.getDeploymentRef()))
                .endpointRef(toJsonString(r.getEndpointRef()))
                .responseColumns(toJsonString(r.getResponseColumns()))
                .requestTemplate(toJsonString(r.getRequestTemplate()))
                .inputBindings(toJsonString(r.getInputBindings()))
                .mcpDeploymentRef(toJsonString(r.getMcpDeploymentRef()))
                .toolRef(toJsonString(r.getToolRef()))
                .argumentTemplate(toJsonString(r.getArgumentTemplate()))
                .overallScore(toJsonString(r.getOverallScore()))
                .valid(r.getIsValid())
                .validationWarnings(toJsonString(r.getValidationWarnings()))
                .version(r.getVersion())
                .createdBy(r.getCreatedBy())
                .createdAt(r.getCreatedAtMs())
                .updatedAt(r.getUpdatedAtMs())
                .build();
    }

    private static String toJsonString(JSONB jsonb) {
        return jsonb != null ? jsonb.data() : null;
    }
}
