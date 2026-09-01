package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("SuiteSnapshotBuilder")
@ExtendWith(MockitoExtension.class)
class SuiteSnapshotBuilderTest {

    @Mock
    private JsonbMapper jsonbMapper;

    @InjectMocks
    private SuiteSnapshotBuilder builder;

    @Nested
    @DisplayName("DEPLOYMENT suite")
    class DeploymentSuite {

        @Test
        @DisplayName("builds snapshot with deployment fields and datasetRef; MCP fields absent")
        void buildsDeploymentSnapshot() {
            DeploymentReferenceDto deploymentRef =
                    DeploymentReferenceDto.builder().id("dep-1").name("Dep One").build();
            EndpointContractDto endpointRef = EndpointContractDto.builder()
                    .method(HttpMethod.POST)
                    .relativeUrlPattern("/chat")
                    .build();
            RequestTemplateDto requestTemplate =
                    RequestTemplateDto.builder().urlTemplate("/chat").build();
            List<InputBindingDto> inputBindings =
                    List.of(InputBindingDto.builder().templateVariable("q").build());
            List<ResponseColumnDefinitionDto> responseColumns = List.of(ResponseColumnDefinitionDto.builder()
                    .name("answer")
                    .expression("$.answer")
                    .type(SchemaFieldType.STRING)
                    .build());
            List<FieldDefinitionDto> testCaseSchema = List.of(FieldDefinitionDto.builder()
                    .name("query")
                    .type(SchemaFieldType.STRING)
                    .build());

            UUID datasetId = UUID.randomUUID();
            TestSuite suite = TestSuite.builder()
                    .suiteType(SuiteType.DEPLOYMENT)
                    .datasetId(datasetId)
                    .deploymentRef("{}")
                    .endpointRef("{}")
                    .requestTemplate("{}")
                    .inputBindings("[]")
                    .responseColumns("[]")
                    .build();
            Dataset dataset = Dataset.builder()
                    .id(datasetId)
                    .name("Dataset A")
                    .version(3L)
                    .testCaseSchema("[]")
                    .build();

            when(jsonbMapper.map(suite.getDeploymentRef())).thenReturn(deploymentRef);
            when(jsonbMapper.mapEndpointContract(suite.getEndpointRef())).thenReturn(endpointRef);
            when(jsonbMapper.mapRequestTemplate(suite.getRequestTemplate())).thenReturn(requestTemplate);
            when(jsonbMapper.mapInputBindings(suite.getInputBindings())).thenReturn(inputBindings);
            when(jsonbMapper.mapResponseColumns(suite.getResponseColumns())).thenReturn(responseColumns);
            when(jsonbMapper.mapFieldDefinitions(dataset.getTestCaseSchema())).thenReturn(testCaseSchema);

            SuiteSnapshotDto snapshot = builder.build(suite, dataset);

            assertThat(snapshot.getSnapshotVersion()).isEqualTo("2");
            assertThat(snapshot.getSuiteType()).isEqualTo("DEPLOYMENT");
            assertThat(snapshot.getDatasetRef()).isNotNull();
            assertThat(snapshot.getDatasetRef().getId()).isEqualTo(datasetId);
            assertThat(snapshot.getDatasetRef().getVersion()).isEqualTo(3L);
            assertThat(snapshot.getDatasetRef().getName()).isEqualTo("Dataset A");
            assertThat(snapshot.getDeploymentRef()).isEqualTo(deploymentRef);
            assertThat(snapshot.getEndpointRef()).isEqualTo(endpointRef);
            assertThat(snapshot.getRequestTemplate()).isEqualTo(requestTemplate);
            assertThat(snapshot.getInputBindings()).isEqualTo(inputBindings);
            assertThat(snapshot.getResponseColumns()).isEqualTo(responseColumns);
            assertThat(snapshot.getTestCaseSchema()).isEqualTo(testCaseSchema);
            // MCP fields must be absent
            assertThat(snapshot.getMcpDeploymentRef()).isNull();
            assertThat(snapshot.getToolRef()).isNull();
            assertThat(snapshot.getArgumentTemplate()).isNull();
        }

        @Test
        @DisplayName("snapshots the request chain: additionalRequests and requestName")
        void buildsSnapshotWithRequestChain() {
            UUID datasetId = UUID.randomUUID();
            TestSuite suite = TestSuite.builder()
                    .suiteType(SuiteType.DEPLOYMENT)
                    .datasetId(datasetId)
                    .deploymentRef("{}")
                    .endpointRef("{}")
                    .requestTemplate("{}")
                    .inputBindings("[]")
                    .responseColumns("[]")
                    .additionalRequests("[{\"name\":\"second\"}]")
                    .requestName("first")
                    .build();
            Dataset dataset = Dataset.builder()
                    .id(datasetId)
                    .name("Dataset A")
                    .version(1L)
                    .testCaseSchema("[]")
                    .build();
            List<RequestDefinitionDto> additionalRequests =
                    List.of(RequestDefinitionDto.builder().name("second").build());

            when(jsonbMapper.map(suite.getDeploymentRef())).thenReturn(null);
            when(jsonbMapper.mapEndpointContract(suite.getEndpointRef())).thenReturn(null);
            when(jsonbMapper.mapRequestTemplate(suite.getRequestTemplate())).thenReturn(null);
            when(jsonbMapper.mapInputBindings(suite.getInputBindings())).thenReturn(List.of());
            when(jsonbMapper.mapResponseColumns(suite.getResponseColumns())).thenReturn(List.of());
            when(jsonbMapper.mapFieldDefinitions(dataset.getTestCaseSchema())).thenReturn(List.of());
            when(jsonbMapper.mapAdditionalRequests(suite.getAdditionalRequests()))
                    .thenReturn(additionalRequests);

            SuiteSnapshotDto snapshot = builder.build(suite, dataset);

            assertThat(snapshot.getRequestName()).isEqualTo("first");
            assertThat(snapshot.getAdditionalRequests()).isEqualTo(additionalRequests);
        }

        @Test
        @DisplayName("stamps snapshotVersion explicitly to CURRENT_VERSION on DEPLOYMENT path "
                + "(guards against silent reliance on @Builder.Default)")
        void shouldStampCurrentVersionExplicitlyForDeployment() {
            UUID datasetId = UUID.randomUUID();
            TestSuite suite = TestSuite.builder()
                    .suiteType(SuiteType.DEPLOYMENT)
                    .datasetId(datasetId)
                    .deploymentRef("{}")
                    .endpointRef("{}")
                    .requestTemplate("{}")
                    .inputBindings("[]")
                    .responseColumns("[]")
                    .build();
            Dataset dataset = Dataset.builder()
                    .id(datasetId)
                    .name("Dataset A")
                    .version(1L)
                    .testCaseSchema("[]")
                    .build();

            when(jsonbMapper.map(suite.getDeploymentRef())).thenReturn(null);
            when(jsonbMapper.mapEndpointContract(suite.getEndpointRef())).thenReturn(null);
            when(jsonbMapper.mapRequestTemplate(suite.getRequestTemplate())).thenReturn(null);
            when(jsonbMapper.mapInputBindings(suite.getInputBindings())).thenReturn(List.of());
            when(jsonbMapper.mapResponseColumns(suite.getResponseColumns())).thenReturn(List.of());
            when(jsonbMapper.mapFieldDefinitions(dataset.getTestCaseSchema())).thenReturn(List.of());

            SuiteSnapshotDto snapshot = builder.build(suite, dataset);

            assertThat(snapshot.getSnapshotVersion()).isEqualTo(SuiteSnapshotDto.CURRENT_VERSION);
            assertThat(SuiteSnapshotDto.CURRENT_VERSION).isEqualTo("2");
        }

        @Test
        @DisplayName("snapshots overallScoreThreshold from the suite")
        void buildsSnapshotWithOverallScoreThreshold() {
            UUID datasetId = UUID.randomUUID();
            TestSuite suite = TestSuite.builder()
                    .suiteType(SuiteType.DEPLOYMENT)
                    .datasetId(datasetId)
                    .deploymentRef("{}")
                    .endpointRef("{}")
                    .requestTemplate("{}")
                    .inputBindings("[]")
                    .responseColumns("[]")
                    .overallScoreThreshold(0.8)
                    .build();
            Dataset dataset = Dataset.builder()
                    .id(datasetId)
                    .name("Dataset A")
                    .version(1L)
                    .testCaseSchema("[]")
                    .build();

            when(jsonbMapper.map(suite.getDeploymentRef())).thenReturn(null);
            when(jsonbMapper.mapEndpointContract(suite.getEndpointRef())).thenReturn(null);
            when(jsonbMapper.mapRequestTemplate(suite.getRequestTemplate())).thenReturn(null);
            when(jsonbMapper.mapInputBindings(suite.getInputBindings())).thenReturn(List.of());
            when(jsonbMapper.mapResponseColumns(suite.getResponseColumns())).thenReturn(List.of());
            when(jsonbMapper.mapFieldDefinitions(dataset.getTestCaseSchema())).thenReturn(List.of());

            SuiteSnapshotDto snapshot = builder.build(suite, dataset);

            assertThat(snapshot.getOverallScoreThreshold()).isEqualTo(0.8);
        }

        @Test
        @DisplayName("snapshots null overallScoreThreshold when the suite has none configured")
        void buildsSnapshotWithNullOverallScoreThreshold() {
            UUID datasetId = UUID.randomUUID();
            TestSuite suite = TestSuite.builder()
                    .suiteType(SuiteType.DEPLOYMENT)
                    .datasetId(datasetId)
                    .deploymentRef("{}")
                    .endpointRef("{}")
                    .requestTemplate("{}")
                    .inputBindings("[]")
                    .responseColumns("[]")
                    .build();
            Dataset dataset = Dataset.builder()
                    .id(datasetId)
                    .name("Dataset A")
                    .version(1L)
                    .testCaseSchema("[]")
                    .build();

            when(jsonbMapper.map(suite.getDeploymentRef())).thenReturn(null);
            when(jsonbMapper.mapEndpointContract(suite.getEndpointRef())).thenReturn(null);
            when(jsonbMapper.mapRequestTemplate(suite.getRequestTemplate())).thenReturn(null);
            when(jsonbMapper.mapInputBindings(suite.getInputBindings())).thenReturn(List.of());
            when(jsonbMapper.mapResponseColumns(suite.getResponseColumns())).thenReturn(List.of());
            when(jsonbMapper.mapFieldDefinitions(dataset.getTestCaseSchema())).thenReturn(List.of());

            SuiteSnapshotDto snapshot = builder.build(suite, dataset);

            assertThat(snapshot.getOverallScoreThreshold()).isNull();
        }

        @Test
        @DisplayName("legacy snapshot JSON without overallScoreThreshold key deserializes as null")
        void legacySnapshotJsonDeserializesNullThreshold() {
            ObjectMapper objectMapper = JsonMapper.builder().build();
            String legacyJson = "{\"snapshotVersion\":\"2\",\"suiteType\":\"DEPLOYMENT\"}";

            SuiteSnapshotDto snapshot = objectMapper.readValue(legacyJson, SuiteSnapshotDto.class);

            assertThat(snapshot.getOverallScoreThreshold()).isNull();
            assertThat(snapshot.getSnapshotVersion()).isEqualTo("2");
        }
    }

    @Nested
    @DisplayName("MCP_TOOL suite")
    class McpToolSuite {

        @Test
        @DisplayName("builds snapshot with MCP fields and datasetRef; deployment fields absent")
        void buildsMcpSnapshot() {
            McpDeploymentReferenceDto mcpRef =
                    McpDeploymentReferenceDto.builder().id("mcp-1").build();
            ToolReferenceDto toolRef = ToolReferenceDto.builder().name("search").build();
            ArgumentTemplateDto argTemplate = ArgumentTemplateDto.builder()
                    .arguments(Map.of("query", "${{query}}"))
                    .build();
            List<InputBindingDto> inputBindings =
                    List.of(InputBindingDto.builder().templateVariable("query").build());
            List<FieldDefinitionDto> testCaseSchema = List.of(FieldDefinitionDto.builder()
                    .name("query")
                    .type(SchemaFieldType.STRING)
                    .build());

            UUID datasetId = UUID.randomUUID();
            TestSuite suite = TestSuite.builder()
                    .suiteType(SuiteType.MCP_TOOL)
                    .datasetId(datasetId)
                    .mcpDeploymentRef("{}")
                    .toolRef("{}")
                    .argumentTemplate("{}")
                    .inputBindings("[]")
                    .responseColumns("[]")
                    .build();
            Dataset dataset = Dataset.builder()
                    .id(datasetId)
                    .name("MCP Dataset")
                    .version(5L)
                    .testCaseSchema("[]")
                    .build();

            when(jsonbMapper.mapMcpDeploymentRef(suite.getMcpDeploymentRef())).thenReturn(mcpRef);
            when(jsonbMapper.mapToolRef(suite.getToolRef())).thenReturn(toolRef);
            when(jsonbMapper.mapArgumentTemplate(suite.getArgumentTemplate())).thenReturn(argTemplate);
            when(jsonbMapper.mapInputBindings(suite.getInputBindings())).thenReturn(inputBindings);
            when(jsonbMapper.mapResponseColumns(suite.getResponseColumns())).thenReturn(List.of());
            when(jsonbMapper.mapFieldDefinitions(dataset.getTestCaseSchema())).thenReturn(testCaseSchema);

            SuiteSnapshotDto snapshot = builder.build(suite, dataset);

            assertThat(snapshot.getSnapshotVersion()).isEqualTo("2");
            assertThat(snapshot.getSuiteType()).isEqualTo("MCP_TOOL");
            assertThat(snapshot.getDatasetRef()).isNotNull();
            assertThat(snapshot.getDatasetRef().getId()).isEqualTo(datasetId);
            assertThat(snapshot.getDatasetRef().getVersion()).isEqualTo(5L);
            assertThat(snapshot.getDatasetRef().getName()).isEqualTo("MCP Dataset");
            assertThat(snapshot.getMcpDeploymentRef()).isEqualTo(mcpRef);
            assertThat(snapshot.getToolRef()).isEqualTo(toolRef);
            assertThat(snapshot.getArgumentTemplate()).isEqualTo(argTemplate);
            assertThat(snapshot.getInputBindings()).isEqualTo(inputBindings);
            // Deployment-only fields must be absent
            assertThat(snapshot.getDeploymentRef()).isNull();
            assertThat(snapshot.getEndpointRef()).isNull();
            assertThat(snapshot.getRequestTemplate()).isNull();
        }

        @Test
        @DisplayName("stamps snapshotVersion explicitly to CURRENT_VERSION on MCP_TOOL path "
                + "(guards against silent reliance on @Builder.Default)")
        void shouldStampCurrentVersionExplicitlyForMcpTool() {
            UUID datasetId = UUID.randomUUID();
            TestSuite suite = TestSuite.builder()
                    .suiteType(SuiteType.MCP_TOOL)
                    .datasetId(datasetId)
                    .mcpDeploymentRef("{}")
                    .toolRef("{}")
                    .argumentTemplate("{}")
                    .inputBindings("[]")
                    .responseColumns("[]")
                    .build();
            Dataset dataset = Dataset.builder()
                    .id(datasetId)
                    .name("MCP Dataset")
                    .version(1L)
                    .testCaseSchema("[]")
                    .build();

            when(jsonbMapper.mapMcpDeploymentRef(suite.getMcpDeploymentRef())).thenReturn(null);
            when(jsonbMapper.mapToolRef(suite.getToolRef())).thenReturn(null);
            when(jsonbMapper.mapArgumentTemplate(suite.getArgumentTemplate())).thenReturn(null);
            when(jsonbMapper.mapInputBindings(suite.getInputBindings())).thenReturn(List.of());
            when(jsonbMapper.mapResponseColumns(suite.getResponseColumns())).thenReturn(List.of());
            when(jsonbMapper.mapFieldDefinitions(dataset.getTestCaseSchema())).thenReturn(List.of());

            SuiteSnapshotDto snapshot = builder.build(suite, dataset);

            assertThat(snapshot.getSnapshotVersion()).isEqualTo(SuiteSnapshotDto.CURRENT_VERSION);
            assertThat(SuiteSnapshotDto.CURRENT_VERSION).isEqualTo("2");
        }
    }
}
