package com.epam.aidial.evaluation.service.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.SuiteType;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteCloneRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestSuiteMapper — clone mapping")
class TestSuiteMapperCloneTest {

    private TestSuiteMapper mapper;
    private final UUID sourceId = UUID.randomUUID();
    private final UUID newId = UUID.randomUUID();
    private final UUID sourceDatasetId = UUID.randomUUID();
    private final String createdBy = "tester";
    private final String sourcePrefix = "@ef/suites/" + sourceId + "/";
    private final String targetPrefix = "@ef/suites/" + newId + "/";

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonbMapper jsonbMapper = new JsonbMapper(objectMapper);
        ValidationWarningsSerializer warningsSerializer = new ValidationWarningsSerializer(objectMapper);
        DisabledTestCaseIdsCodec disabledTestCaseIdsCodec = new DisabledTestCaseIdsCodec(objectMapper);
        mapper = new TestSuiteMapper(jsonbMapper, warningsSerializer, disabledTestCaseIdsCodec);
    }

    // -----------------------------------------------------------------------
    // toCloneEntity — null-means-inherit
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("inherits all optional fields from source when DTO has only name")
    void toCloneEntity_inheritsAllFromSource_whenDtoHasOnlyName() {
        TestSuite source = sourceWithAllFields();
        TestSuiteCloneRequestDto dto =
                TestSuiteCloneRequestDto.builder().name("Cloned Suite").build();

        TestSuite cloned = mapper.toCloneEntity(source, dto, newId, createdBy);

        assertThat(cloned.getId()).isEqualTo(newId);
        assertThat(cloned.getName()).isEqualTo("Cloned Suite");
        assertThat(cloned.getDescription()).isEqualTo(source.getDescription());
        assertThat(cloned.getSuiteType()).isEqualTo(source.getSuiteType());
        assertThat(cloned.getDatasetId()).isEqualTo(source.getDatasetId());
        assertThat(cloned.getDisabledTestCaseIds()).isEqualTo(source.getDisabledTestCaseIds());
        assertThat(cloned.getDeploymentRef()).isEqualTo(source.getDeploymentRef());
        assertThat(cloned.getEndpointRef()).isEqualTo(source.getEndpointRef());
        assertThat(cloned.getResponseColumns()).isEqualTo(source.getResponseColumns());
        assertThat(cloned.getMcpDeploymentRef()).isEqualTo(source.getMcpDeploymentRef());
        assertThat(cloned.getToolRef()).isEqualTo(source.getToolRef());
        assertThat(cloned.getOverallScoreThreshold()).isEqualTo(source.getOverallScoreThreshold());
        assertThat(cloned.getCreatedBy()).isEqualTo(createdBy);
        assertThat(cloned.getVersion()).isEqualTo(0L);
    }

    @Test
    @DisplayName("applies description override when DTO has description")
    void toCloneEntity_appliesDescriptionOverride_whenDtoHasDescription() {
        TestSuite source = sourceWithAllFields();
        TestSuiteCloneRequestDto dto = TestSuiteCloneRequestDto.builder()
                .name("Clone")
                .description("Overridden description")
                .build();

        TestSuite cloned = mapper.toCloneEntity(source, dto, newId, createdBy);

        assertThat(cloned.getDescription()).isEqualTo("Overridden description");
    }

    @Test
    @DisplayName("always inherits suiteType from source — suiteType cannot be overridden via clone request")
    void toCloneEntity_alwaysInheritsSuiteTypeFromSource() {
        TestSuite source = sourceWithAllFields(); // suiteType = DEPLOYMENT
        // DTO has no suiteType field — suiteType is always taken from source
        TestSuiteCloneRequestDto dto =
                TestSuiteCloneRequestDto.builder().name("Clone").build();

        TestSuite cloned = mapper.toCloneEntity(source, dto, newId, createdBy);

        assertThat(cloned.getSuiteType()).isEqualTo(SuiteType.DEPLOYMENT);
    }

    @Test
    @DisplayName("applies datasetId override when DTO has datasetId")
    void toCloneEntity_appliesDatasetIdOverride_whenDtoHasDatasetId() {
        TestSuite source = sourceWithAllFields();
        UUID overrideDatasetId = UUID.randomUUID();
        TestSuiteCloneRequestDto dto = TestSuiteCloneRequestDto.builder()
                .name("Clone")
                .datasetId(overrideDatasetId)
                .build();

        TestSuite cloned = mapper.toCloneEntity(source, dto, newId, createdBy);

        assertThat(cloned.getDatasetId()).isEqualTo(overrideDatasetId);
        assertThat(cloned.getDatasetId()).isNotEqualTo(source.getDatasetId());
    }

    @Test
    @DisplayName("applies deploymentRef override when DTO has deploymentRef")
    void toCloneEntity_appliesDeploymentRefOverride_whenDtoHasDeploymentRef() {
        TestSuite source = sourceWithAllFields();
        TestSuiteCloneRequestDto dto = TestSuiteCloneRequestDto.builder()
                .name("Clone")
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("new-deploy")
                        .name("New")
                        .build())
                .build();

        TestSuite cloned = mapper.toCloneEntity(source, dto, newId, createdBy);

        assertThat(cloned.getDeploymentRef()).contains("new-deploy");
        assertThat(cloned.getDeploymentRef()).doesNotContain("source-deploy");
    }

    // -----------------------------------------------------------------------
    // toCloneEntity — file ref rewriting
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("rewrites file refs in inputBindings when inherited from source")
    void toCloneEntity_rewritesFileRefsInInputBindings_whenInherited() {
        TestSuite source = sourceWithAllFields();
        source.setInputBindings("[\"" + sourcePrefix + "bindings.json\"]");
        TestSuiteCloneRequestDto dto =
                TestSuiteCloneRequestDto.builder().name("Clone").build();

        TestSuite cloned = mapper.toCloneEntity(source, dto, newId, createdBy);

        assertThat(cloned.getInputBindings()).contains(targetPrefix);
        assertThat(cloned.getInputBindings()).doesNotContain(sourcePrefix);
    }

    @Test
    @DisplayName("rewrites file refs in requestTemplate when inherited from source")
    void toCloneEntity_rewritesFileRefsInRequestTemplate_whenInherited() {
        TestSuite source = sourceWithAllFields();
        source.setRequestTemplate("{\"url\":\"" + sourcePrefix + "template.json\"}");
        TestSuiteCloneRequestDto dto =
                TestSuiteCloneRequestDto.builder().name("Clone").build();

        TestSuite cloned = mapper.toCloneEntity(source, dto, newId, createdBy);

        assertThat(cloned.getRequestTemplate()).contains(targetPrefix);
        assertThat(cloned.getRequestTemplate()).doesNotContain(sourcePrefix);
    }

    @Test
    @DisplayName("rewrites file refs in argumentTemplate when inherited from source")
    void toCloneEntity_rewritesFileRefsInArgumentTemplate_whenInherited() {
        TestSuite source = sourceWithAllFields();
        source.setArgumentTemplate("{\"arg\":\"" + sourcePrefix + "args.json\"}");
        TestSuiteCloneRequestDto dto =
                TestSuiteCloneRequestDto.builder().name("Clone").build();

        TestSuite cloned = mapper.toCloneEntity(source, dto, newId, createdBy);

        assertThat(cloned.getArgumentTemplate()).contains(targetPrefix);
        assertThat(cloned.getArgumentTemplate()).doesNotContain(sourcePrefix);
    }

    @Test
    @DisplayName("does not rewrite file refs in deploymentRef (not a file-ref field)")
    void toCloneEntity_doesNotRewriteFileRefsInDeploymentRef() {
        TestSuite source = sourceWithAllFields();
        // Simulate a deploymentRef that happens to contain the suite path string (edge case)
        source.setDeploymentRef("{\"id\":\"" + sourcePrefix + "ref\"}");
        TestSuiteCloneRequestDto dto =
                TestSuiteCloneRequestDto.builder().name("Clone").build();

        TestSuite cloned = mapper.toCloneEntity(source, dto, newId, createdBy);

        // deploymentRef is NOT rewritten — it is not a file-ref field
        assertThat(cloned.getDeploymentRef()).contains(sourcePrefix);
    }

    @Test
    @DisplayName("leaves null inputBindings as null after cloning")
    void toCloneEntity_leavesNullInputBindingsAsNull() {
        TestSuite source = sourceWithAllFields();
        source.setInputBindings(null);
        TestSuiteCloneRequestDto dto =
                TestSuiteCloneRequestDto.builder().name("Clone").build();

        TestSuite cloned = mapper.toCloneEntity(source, dto, newId, createdBy);

        assertThat(cloned.getInputBindings()).isNull();
    }

    // -----------------------------------------------------------------------
    // toCloneEntity — isValid and validationWarnings are NOT set by mapper
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("does not set isValid or validationWarnings — those are set by validation step")
    void toCloneEntity_doesNotSetIsValidOrValidationWarnings() {
        TestSuite source = sourceWithAllFields();
        source.setValid(true);
        source.setValidationWarnings("[]");
        TestSuiteCloneRequestDto dto =
                TestSuiteCloneRequestDto.builder().name("Clone").build();

        TestSuite cloned = mapper.toCloneEntity(source, dto, newId, createdBy);

        // Lombok @Builder defaults boolean to false and String to null
        assertThat(cloned.isValid()).isFalse();
        assertThat(cloned.getValidationWarnings()).isNull();
    }

    // -----------------------------------------------------------------------
    // toRequestDto — reverse-mapping entity → DTO
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("maps all fields from entity to request DTO including datasetId and disabledTestCaseIds")
    void toRequestDto_mapsAllFields_fromEntity() {
        UUID datasetId = UUID.randomUUID();
        UUID disabledId = UUID.randomUUID();
        TestSuite entity = TestSuite.builder()
                .name("Suite A")
                .description("Desc A")
                .suiteType(SuiteType.DEPLOYMENT)
                .datasetId(datasetId)
                .disabledTestCaseIds("[\"" + disabledId + "\"]")
                .deploymentRef("{\"id\":\"d1\",\"name\":\"D1\"}")
                .endpointRef("{\"method\":\"POST\",\"relativeUrlPattern\":\"/v1/chat\"}")
                .responseColumns("[]")
                .inputBindings("[]")
                .overallScoreThreshold(0.75)
                .build();

        TestSuiteRequestDto dto = mapper.toRequestDto(entity);

        assertThat(dto).isNotNull();
        assertThat(dto.getName()).isEqualTo("Suite A");
        assertThat(dto.getDescription()).isEqualTo("Desc A");
        assertThat(dto.getSuiteType()).isEqualTo(SuiteType.DEPLOYMENT);
        assertThat(dto.getDatasetId()).isEqualTo(datasetId);
        assertThat(dto.getDisabledTestCaseIds()).containsExactly(disabledId);
        assertThat(dto.getDeploymentRef()).isNotNull();
        assertThat(dto.getDeploymentRef().getId()).isEqualTo("d1");
        assertThat(dto.getEndpointRef()).isNotNull();
        assertThat(dto.getEndpointRef().getMethod()).isEqualTo(HttpMethod.POST);
        assertThat(dto.getResponseColumns()).isEmpty();
        assertThat(dto.getInputBindings()).isEmpty();
        assertThat(dto.getOverallScoreThreshold()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("returns null DTO fields when entity JSONB fields are null")
    void toRequestDto_returnsNullDtoFields_whenEntityJsonbIsNull() {
        TestSuite entity = TestSuite.builder()
                .name("Minimal")
                .suiteType(SuiteType.DEPLOYMENT)
                .datasetId(UUID.randomUUID())
                .disabledTestCaseIds(null)
                .deploymentRef(null)
                .endpointRef(null)
                .responseColumns(null)
                .inputBindings(null)
                .mcpDeploymentRef(null)
                .toolRef(null)
                .argumentTemplate(null)
                .requestTemplate(null)
                .overallScoreThreshold(null)
                .build();

        TestSuiteRequestDto dto = mapper.toRequestDto(entity);

        assertThat(dto.getDeploymentRef()).isNull();
        assertThat(dto.getEndpointRef()).isNull();
        assertThat(dto.getDisabledTestCaseIds()).isNull();
        assertThat(dto.getResponseColumns()).isNull();
        assertThat(dto.getInputBindings()).isNull();
        assertThat(dto.getMcpDeploymentRef()).isNull();
        assertThat(dto.getToolRef()).isNull();
        assertThat(dto.getArgumentTemplate()).isNull();
        assertThat(dto.getRequestTemplate()).isNull();
        assertThat(dto.getOverallScoreThreshold()).isNull();
    }

    @Test
    @DisplayName("defaults suiteType to DEPLOYMENT when entity suiteType is null")
    void toRequestDto_defaultsSuiteTypeToDeployment_whenEntitySuiteTypeIsNull() {
        TestSuite entity = TestSuite.builder().name("No Type").suiteType(null).build();

        TestSuiteRequestDto dto = mapper.toRequestDto(entity);

        assertThat(dto.getSuiteType()).isEqualTo(SuiteType.DEPLOYMENT);
    }

    @Test
    @DisplayName("returns null when entity is null")
    void toRequestDto_returnsNull_whenEntityIsNull() {
        assertThat(mapper.toRequestDto(null)).isNull();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private TestSuite sourceWithAllFields() {
        return TestSuite.builder()
                .id(sourceId)
                .name("Source Suite")
                .description("Source description")
                .suiteType(SuiteType.DEPLOYMENT)
                .datasetId(sourceDatasetId)
                .disabledTestCaseIds("[]")
                .deploymentRef("{\"id\":\"source-deploy\",\"name\":\"Source Deployment\"}")
                .endpointRef("{\"method\":\"POST\",\"relativeUrlPattern\":\"/v1/chat\"}")
                .responseColumns("[]")
                .inputBindings("[]")
                .requestTemplate("{\"url\":\"/v1/chat\"}")
                .argumentTemplate(null)
                .mcpDeploymentRef(null)
                .toolRef(null)
                .overallScoreThreshold(0.8)
                .valid(true)
                .validationWarnings("[]")
                .version(3L)
                .createdBy("original-creator")
                .build();
    }
}
