package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.service.domain.dto.DatasetReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds a {@link SuiteSnapshotDto} from a live ({@link TestSuite}, {@link Dataset}) pair.
 * Schema is sourced from the dataset (the suite no longer owns {@code testCaseSchema}); the
 * dataset's id/version/name are captured into {@link DatasetReferenceDto} so the snapshot
 * records which dataset (and which version of it) the run was bound to.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class SuiteSnapshotBuilder {

    private final JsonbMapper jsonbMapper;

    public SuiteSnapshotDto build(TestSuite suite, Dataset dataset) {
        SuiteSnapshotDto.SuiteSnapshotDtoBuilder builder = SuiteSnapshotDto.builder()
                .snapshotVersion(SuiteSnapshotDto.CURRENT_VERSION)
                .suiteType(suite.getSuiteType() != null ? suite.getSuiteType().name() : null)
                .datasetRef(toDatasetRef(dataset))
                .responseColumns(jsonbMapper.mapResponseColumns(suite.getResponseColumns()))
                .testCaseSchema(jsonbMapper.mapFieldDefinitions(dataset.getTestCaseSchema()))
                // Stored verbatim (null = system default); the single-metric default is resolved at Phase 3.
                .overallScore(jsonbMapper.mapOverallScore(suite.getOverallScore()));

        if (suite.getSuiteType() == SuiteType.MCP_TOOL) {
            builder.mcpDeploymentRef(jsonbMapper.mapMcpDeploymentRef(suite.getMcpDeploymentRef()))
                    .toolRef(jsonbMapper.mapToolRef(suite.getToolRef()))
                    .argumentTemplate(jsonbMapper.mapArgumentTemplate(suite.getArgumentTemplate()))
                    .inputBindings(jsonbMapper.mapInputBindings(suite.getInputBindings()));
        } else {
            builder.deploymentRef(jsonbMapper.map(suite.getDeploymentRef()))
                    .endpointRef(jsonbMapper.mapEndpointContract(suite.getEndpointRef()))
                    .requestTemplate(jsonbMapper.mapRequestTemplate(suite.getRequestTemplate()))
                    .inputBindings(jsonbMapper.mapInputBindings(suite.getInputBindings()))
                    .multiTurn(suite.isMultiTurn());
        }

        return builder.build();
    }

    private static DatasetReferenceDto toDatasetRef(Dataset dataset) {
        return DatasetReferenceDto.builder()
                .id(dataset.getId())
                .version(dataset.getVersion())
                .name(dataset.getName())
                .build();
    }
}
