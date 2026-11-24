package com.epam.aidial.evaluation.data.db.mapper;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITES;

import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.TestSuitesRecord;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.model.MetricScoreAggregation;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import java.util.UUID;
import org.jooq.JSONB;
import org.jooq.Record;
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
                .deploymentRef(toJsonString(r.getDeploymentRef()))
                .routesRef(toJsonString(r.getRoutesRef()))
                .endpointRef(toJsonString(r.getEndpointRef()))
                .responseColumns(toJsonString(r.getResponseColumns()))
                .requestTemplate(toJsonString(r.getRequestTemplate()))
                .inputBindings(toJsonString(r.getInputBindings()))
                .mcpDeploymentRef(toJsonString(r.getMcpDeploymentRef()))
                .toolRef(toJsonString(r.getToolRef()))
                .argumentTemplate(toJsonString(r.getArgumentTemplate()))
                .additionalRequests(toJsonString(r.getAdditionalRequests()))
                .requestName(r.getRequestName())
                .overallScore(toJsonString(r.getOverallScore()))
                .testCaseOverallScore(toJsonString(r.getTestCaseOverallScore()))
                .overallScoreThreshold(r.getOverallScoreThreshold())
                .metricScoreAggregation(MetricScoreAggregation.fromValue(r.getMetricScoreAggregation()))
                .testCaseFilter(toJsonString(r.getTestCaseFilter()))
                .valid(r.getIsValid())
                .validationWarnings(toJsonString(r.getValidationWarnings()))
                .version(r.getVersion())
                .createdBy(r.getCreatedBy())
                .createdAt(r.getCreatedAtMs())
                .updatedAt(r.getUpdatedAtMs())
                .build();
    }

    public TestSuite mapProjection(Record r) {
        return TestSuite.builder()
                .id(UUID.fromString(r.get(TEST_SUITES.ID)))
                .name(r.get(TEST_SUITES.NAME))
                .suiteType(SuiteType.fromValue(r.get(TEST_SUITES.SUITE_TYPE)))
                .deploymentRef(toJsonString(r.get(TEST_SUITES.DEPLOYMENT_REF)))
                .routesRef(toJsonString(r.get(TEST_SUITES.ROUTES_REF)))
                .mcpDeploymentRef(toJsonString(r.get(TEST_SUITES.MCP_DEPLOYMENT_REF)))
                .toolRef(toJsonString(r.get(TEST_SUITES.TOOL_REF)))
                .build();
    }

    private static String toJsonString(JSONB jsonb) {
        return jsonb != null ? jsonb.data() : null;
    }
}
