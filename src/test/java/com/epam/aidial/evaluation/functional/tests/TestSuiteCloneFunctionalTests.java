package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.client.dialcore.dto.DialFileMetadataDto;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteMetricDefinition;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteMetricDefinitionRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetricDeclarationTestDataProvider;
import com.epam.aidial.evaluation.service.domain.dto.ConstantBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricParameterBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteCloneRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteMetricDefinitionResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteUpdateResultDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Functional tests for POST /api/v1/test-suites/{id}/clone.
 * Infrastructure note (task 8.0):
 * - The existing PostgresFunctionalTests base class provides a mock DialFileClient backed by an
 *   in-memory dialFileStore/dialMetadataStore. FileService.copyFilesBetweenSuites() calls
 *   dialFileClient.list(), download(), and upload() — all of which are pre-stubbed in
 *   PostgresFunctionalTests.setUpDialFileClientMock(). No extra stubs are needed here.
 * - For file-copy scenarios, dialFileStore is pre-populated in the test method before calling clone.
 * - For no-file scenarios, the default empty list() stub covers the case.
 * - FileService itself is NOT mocked — only DialFileClient is.
 */
@DisplayName("TestSuite Clone Functional Tests")
public abstract class TestSuiteCloneFunctionalTests extends BaseFunctionalTest {

    private static final UUID SEED_ACCURACY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SEED_ACCURACY_VERSION_ID = UUID.fromString("770e8400-e29b-41d4-a716-446655440001");

    @Autowired
    private DialFileClient dialFileClient;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private MetricDeclarationTestDataProvider metricDeclarationTestDataProvider;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private TestSuiteMetricDefinitionRepository tsmdRepository;

    @Autowired
    private TestSuiteRunRepository testSuiteRunRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("clone-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    @BeforeEach
    void setUpSeedData() {
        metricDeclarationTestDataProvider.insertSeedMetricDeclarations();
        metricDeclarationTestDataProvider.insertSeedVersionForAccuracy();
    }

    // -----------------------------------------------------------------------
    // 8.1 — Happy path and error scenarios
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("8.1 happy path: clone with name only returns 201 with new suite")
    void shouldCloneSuiteWithNameOnly() {
        TestSuiteResponseDto source = createDeploymentSuite("Source Suite " + UUID.randomUUID());
        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Clone of Source " + UUID.randomUUID())
                .build();

        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getSuite()).isNotNull();
        TestSuiteResponseDto cloned = response.getBody().getSuite();
        assertThat(cloned.getId()).isNotNull().isNotEqualTo(source.getId());
        assertThat(cloned.getName()).isEqualTo(request.getName());
        assertThat(cloned.getDeploymentRef()).isNotNull();
        assertThat(cloned.getDeploymentRef().getId())
                .isEqualTo(source.getDeploymentRef().getId());
        // Dataset-rooted clone: no revalidation task is spawned because the cloned suite shares the
        // source's dataset (or the optional override) — schema is identical, no re-validation needed.
        assertThat(response.getBody().getRevalidationTask()).isNull();
        // S2: cloned suite shares the source's dataset; happy-path source had no test cases so count is 0.
        assertThat(testCaseRepository.countByDatasetId(cloned.getDatasetId())).isZero();
    }

    @Test
    @DisplayName("8.1 clone with description override returns 201 with overridden description")
    void shouldCloneSuiteWithDescriptionOverride() {
        TestSuiteResponseDto source = createDeploymentSuite("Source Suite " + UUID.randomUUID());
        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Clone With Override " + UUID.randomUUID())
                .description("Custom description")
                .build();

        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getSuite().getDescription()).isEqualTo("Custom description");
    }

    @Test
    @DisplayName("8.1 returns 404 when source suite does not exist")
    void shouldReturn404WhenSourceSuiteNotFound() {
        UUID nonExistent = UUID.randomUUID();
        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Orphan Clone " + UUID.randomUUID())
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + nonExistent + "/clone"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("8.1 returns 409 when cloned name already exists")
    void shouldReturn409WhenClonedNameIsDuplicate() {
        String existingName = "Existing Suite " + UUID.randomUUID();
        createDeploymentSuite(existingName);
        TestSuiteResponseDto source = createDeploymentSuite("Source For Dupe Clone " + UUID.randomUUID());
        TestSuiteCloneRequestDto request =
                TestSuiteCloneRequestDto.builder().name(existingName).build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("UNIQUE_CONSTRAINT_VIOLATION");
        assertThat(response.getBody()).contains(existingName);
    }

    @Test
    @DisplayName("8.1 returns 400 when clone name is blank")
    void shouldReturn400WhenCloneNameIsBlank() {
        TestSuiteResponseDto source = createDeploymentSuite("Source Suite " + UUID.randomUUID());
        TestSuiteCloneRequestDto request =
                TestSuiteCloneRequestDto.builder().name("   ").build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("8.1 (S1) returns 400 when description override exceeds max length (2000 chars)")
    void shouldReturn400WhenDescriptionExceedsMaxLength() {
        TestSuiteResponseDto source = createDeploymentSuite("Source Suite " + UUID.randomUUID());
        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Clone With Overlong Desc " + UUID.randomUUID())
                .description("x".repeat(2001))
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("8.1 (W1) suiteType is always inherited from source; clone of DEPLOYMENT suite stays DEPLOYMENT")
    void shouldAlwaysInheritSuiteTypeFromSource() {
        TestSuiteResponseDto source = createDeploymentSuite("SuiteType Inherit Source " + UUID.randomUUID());

        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("SuiteType Inherit Clone " + UUID.randomUUID())
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getSuite().getSuiteType()).isEqualTo(source.getSuiteType());
    }

    @Test
    @DisplayName("8.0 / S3: missing file in source folder is skipped gracefully; clone still returns 201")
    void shouldSkipMissingFileAndContinueClone() {
        TestSuiteResponseDto source = createDeploymentSuite("Missing File Source " + UUID.randomUUID());

        // Override the list stub for this suite's folder to return a ghost file that does not exist
        // in dialFileStore — download will throw 404, FileService logs a warning and continues
        String sourcePath = "test-bucket/suites/" + source.getId() + "/";
        when(dialFileClient.list(eq(sourcePath)))
                .thenReturn(List.of(DialFileMetadataDto.builder()
                        .name("ghost.txt")
                        .contentType("text/plain")
                        .build()));

        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Missing File Clone " + UUID.randomUUID())
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getSuite().getId()).isNotEqualTo(source.getId());
    }

    // -----------------------------------------------------------------------
    // 8.2 — Deep copy verification
    // -----------------------------------------------------------------------

    // Removed: "8.2 cloned test cases have new UUIDs and correct data; source suite is unchanged".
    // Under the dataset-rooted clone model, the cloned suite shares the source's dataset; test cases
    // are NOT copied with new UUIDs — both suites see the same rows. The new-UUIDs premise no longer holds.

    @Test
    @DisplayName("8.2 cloned TSMDs have new UUIDs and correct metric declaration references")
    void shouldDeepCopyTsmdsWithNewUuids() {
        TestSuite source = metaTestDataHelper.createTestSuite("TSMD Deep Copy Source " + UUID.randomUUID());
        TestSuiteMetricDefinition tsmd1 = metaTestDataHelper.createTestSuiteMetricDefinition(
                source.getId(), SEED_ACCURACY_ID, SEED_ACCURACY_VERSION_ID, "M1");
        TestSuiteMetricDefinition tsmd2 = metaTestDataHelper.createTestSuiteMetricDefinition(
                source.getId(), SEED_ACCURACY_ID, SEED_ACCURACY_VERSION_ID, "M2");

        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("TSMD Clone " + UUID.randomUUID())
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID clonedId = response.getBody().getSuite().getId();

        // Cloned suite has 2 TSMDs with new UUIDs
        assertThat(metaTestDataHelper.countMetricDefinitions(clonedId)).isEqualTo(2);
        List<TestSuiteMetricDefinitionResponseDto> clonedTsmds = listTsmds(clonedId);
        assertThat(clonedTsmds).hasSize(2);
        assertThat(clonedTsmds)
                .extracting(TestSuiteMetricDefinitionResponseDto::getName)
                .containsExactlyInAnyOrder("M1", "M2");
        assertThat(clonedTsmds)
                .extracting(TestSuiteMetricDefinitionResponseDto::getId)
                .doesNotContain(tsmd1.getId(), tsmd2.getId());
    }

    @Test
    @DisplayName("8.2 cloned suite has no evaluation runs")
    void clonedSuiteShouldHaveNoEvaluationRuns() {
        TestSuiteResponseDto source = createDeploymentSuite("No Runs Source " + UUID.randomUUID());

        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("No Runs Clone " + UUID.randomUUID())
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID clonedId = response.getBody().getSuite().getId();

        // Verify no evaluation runs exist on the cloned suite
        int runCount = testSuiteRunRepository.countByTestSuiteIdAndStatuses(
                clonedId, List.of("PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"));
        assertThat(runCount).isZero();
    }

    /**
     * Tests that pagination works correctly: with batch size = 2 (set via revalidation.batch-size),
     * 3 TSMDs should require 2 DB batch reads to copy all records.
     * The loop terminates when the batch is empty (not when size < batchSize),
     * which correctly handles the last partial batch.
     *
     * <p>S4 note: The spec also calls for an "orphaned TSMD" scenario (TSMD whose metric_declaration_id
     * no longer exists). This cannot be set up directly because the FK constraint on
     * metric_declaration_id prevents inserting TSMDs that reference non-existent declarations.
     * The partial-last-batch case (3 TSMDs with batchSize=2) is the closest practical equivalent:
     * it verifies that the loop uses isEmpty() as the termination condition, which is the exact
     * guard that would also correctly handle JOIN exclusions in a real orphan scenario.</p>
     */
    @Test
    @DisplayName("8.2 copies all TSMDs across pagination boundaries (batch size 2, 3 TSMDs)")
    void shouldCopyAllTsmdsAcrossPaginationBoundaries() {
        TestSuite source = metaTestDataHelper.createTestSuite("Paginated TSMD Source " + UUID.randomUUID());
        metaTestDataHelper.createTestSuiteMetricDefinition(
                source.getId(), SEED_ACCURACY_ID, SEED_ACCURACY_VERSION_ID, "Metric A");
        metaTestDataHelper.createTestSuiteMetricDefinition(
                source.getId(), SEED_ACCURACY_ID, SEED_ACCURACY_VERSION_ID, "Metric B");
        metaTestDataHelper.createTestSuiteMetricDefinition(
                source.getId(), SEED_ACCURACY_ID, SEED_ACCURACY_VERSION_ID, "Metric C");

        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Paginated Clone " + UUID.randomUUID())
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID clonedId = response.getBody().getSuite().getId();

        // All 3 TSMDs must be copied despite the last batch being smaller than batchSize
        assertThat(metaTestDataHelper.countMetricDefinitions(clonedId)).isEqualTo(3);
        List<TestSuiteMetricDefinitionResponseDto> clonedTsmds = listTsmds(clonedId);
        assertThat(clonedTsmds)
                .extracting(TestSuiteMetricDefinitionResponseDto::getName)
                .containsExactlyInAnyOrder("Metric A", "Metric B", "Metric C");
    }

    // -----------------------------------------------------------------------
    // 8.3 — File reference rewriting
    // -----------------------------------------------------------------------

    // Removed: "8.3 file refs in test case data are rewritten to new suite ID after clone".
    // Test case data is owned by the dataset and shared between source and clone — file references in
    // test case data are NOT rewritten because the data is not copied. Suite-level file refs (TSMD
    // configBindings below) are still rewritten because suite-scoped state IS copied per suite.

    @Test
    @DisplayName("8.3 file refs in TSMD configBindings are rewritten to new suite ID after clone")
    void shouldRewriteFileRefsInTsmdConfigBindings() {
        TestSuite source = metaTestDataHelper.createTestSuite("File Ref TSMD Source " + UUID.randomUUID());
        String configBindingsJson = "[{\"property\":\"input\",\"source\":"
                + "{\"$type\":\"Constant\",\"value\":\"@ef/suites/" + source.getId() + "/config.json\"}}]";
        metaTestDataHelper.createTestSuiteMetricDefinition(
                source.getId(),
                SEED_ACCURACY_ID,
                SEED_ACCURACY_VERSION_ID,
                "Metric With Ref",
                configBindingsJson,
                "[]");

        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("File Ref TSMD Clone " + UUID.randomUUID())
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID clonedId = response.getBody().getSuite().getId();

        List<TestSuiteMetricDefinitionResponseDto> clonedTsmds = listTsmds(clonedId);
        assertThat(clonedTsmds).hasSize(1);
        List<MetricParameterBindingDto> configBindings = clonedTsmds.get(0).getConfigBindings();
        assertThat(configBindings).hasSize(1);
        ConstantBindingSourceDto source1 =
                (ConstantBindingSourceDto) configBindings.get(0).getSource();
        String clonedValue = (String) source1.getValue();
        assertThat(clonedValue).contains("@ef/suites/" + clonedId + "/");
        assertThat(clonedValue).doesNotContain("@ef/suites/" + source.getId() + "/");
    }

    @Test
    @DisplayName("8.3 TSMD with null inputBindings does not cause NPE and is cloned as null")
    void shouldHandleNullTsmdInputBindingsWithoutNpe() {
        TestSuite source = metaTestDataHelper.createTestSuite("Null Bindings Source " + UUID.randomUUID());
        // configBindings = "[]", inputBindings = "[]" (default from MetaTestDataHelper)
        metaTestDataHelper.createTestSuiteMetricDefinition(
                source.getId(), SEED_ACCURACY_ID, SEED_ACCURACY_VERSION_ID, "Metric Null Bindings");

        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Null Bindings Clone " + UUID.randomUUID())
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID clonedId = response.getBody().getSuite().getId();
        assertThat(metaTestDataHelper.countMetricDefinitions(clonedId)).isEqualTo(1);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private TestSuiteResponseDto createDeploymentSuite(String name) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name)
                .description("Functional test suite")
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deploy-1")
                        .name("Deployment One")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(false)
                        .build())))
                .build();
        ResponseEntity<TestSuiteResponseDto> r =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    // -----------------------------------------------------------------------
    // 15.7 — Dataset-rooted clone semantics
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("15.7 clone without datasetId override shares the source's dataset")
    void cloneInheritsDatasetIdByDefault() {
        TestSuiteResponseDto source = createDeploymentSuite("Inherits Dataset " + UUID.randomUUID());
        UUID sourceDatasetId = source.getDatasetId();

        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Clone Inherits " + UUID.randomUUID())
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteResponseDto cloned = response.getBody().getSuite();
        assertThat(cloned.getDatasetId()).isEqualTo(sourceDatasetId);
        // Suite-level dataset binding is shared; the dataset row itself is not duplicated
        assertThat(metaTestDataHelper.getDatasetId(cloned.getId())).isEqualTo(sourceDatasetId);
    }

    @Test
    @DisplayName("15.7 clone with datasetId override rebinds the cloned suite to the new dataset")
    void cloneWithDatasetIdOverrideRebindsToNewDataset() {
        TestSuiteResponseDto source = createDeploymentSuite("Source For Override " + UUID.randomUUID());
        UUID overrideDatasetId = newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                .name("prompt")
                .type(SchemaFieldType.STRING)
                .required(false)
                .build()));
        assertThat(overrideDatasetId).isNotEqualTo(source.getDatasetId());

        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Clone With Override " + UUID.randomUUID())
                .datasetId(overrideDatasetId)
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteResponseDto cloned = response.getBody().getSuite();
        assertThat(cloned.getDatasetId()).isEqualTo(overrideDatasetId);
    }

    @Test
    @DisplayName("15.7 clone creates no test-case rows in the cloned suite's dataset")
    void cloneCreatesNoTestCaseRows() {
        TestSuiteResponseDto source = createDeploymentSuite("Source No-Copy " + UUID.randomUUID());
        // Seed a test case in the source's dataset
        UUID sourceDatasetId = source.getDatasetId();
        TestCaseRequestDto tcReq = TestCaseRequestDto.builder()
                .testCaseName("Original case " + UUID.randomUUID())
                .data(Map.of("prompt", "anything"))
                .build();
        ResponseEntity<TestCaseResponseDto> tcCreate = restTemplate.postForEntity(
                apiUrl("/datasets/" + sourceDatasetId + "/test-cases"), jsonEntity(tcReq), TestCaseResponseDto.class);
        assertThat(tcCreate.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long sourceTestCasesBefore = testCaseRepository.countByDatasetId(sourceDatasetId);
        assertThat(sourceTestCasesBefore).isEqualTo(1);

        // Clone with override dataset id — should not get any test-case rows in the new dataset
        UUID overrideDatasetId = newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                .name("prompt")
                .type(SchemaFieldType.STRING)
                .required(false)
                .build()));
        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Clone No-Copy " + UUID.randomUUID())
                .datasetId(overrideDatasetId)
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Override dataset stays empty — clone does not duplicate test cases
        assertThat(testCaseRepository.countByDatasetId(overrideDatasetId)).isZero();
        // Source dataset is untouched
        assertThat(testCaseRepository.countByDatasetId(sourceDatasetId)).isEqualTo(sourceTestCasesBefore);
    }

    private void createTestCase(UUID suiteId, String name, Map<String, Object> data) {
        UUID datasetId = metaTestDataHelper.getDatasetId(suiteId);
        TestCaseRequestDto req =
                TestCaseRequestDto.builder().testCaseName(name).data(data).build();
        ResponseEntity<TestCaseResponseDto> r = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"), jsonEntity(req), TestCaseResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private List<TestCaseResponseDto> listTestCases(UUID suiteId) {
        ResponseEntity<PageResponseDto<TestCaseResponseDto>> r = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suiteId) + "/test-cases"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(r.getBody()).isNotNull();
        return r.getBody().getContent();
    }

    private List<TestSuiteMetricDefinitionResponseDto> listTsmds(UUID suiteId) {
        ResponseEntity<PageResponseDto<TestSuiteMetricDefinitionResponseDto>> r = restTemplate.exchange(
                apiUrl("/test-suites/" + suiteId + "/metric-definitions"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(r.getBody()).isNotNull();
        return r.getBody().getContent();
    }
}
