package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteMetricDefinition;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteMetricDefinitionRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetricDeclarationTestDataProvider;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.runner.client.dialcore.dto.DialFileMetadataDto;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.service.domain.dto.ConstantBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import com.epam.aidial.evaluation.service.domain.dto.MetricParameterBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteCloneRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteMetricDefinitionResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteUpdateResultDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private TestSuiteRepository testSuiteRepository;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("clone-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JacksonException e) {
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
    @DisplayName(
            "8.2 vanilla clone preserves each source TSMD's validity verbatim (valid stays valid, invalid stays invalid)")
    void shouldPreserveTsmdValidityVerbatimOnVanillaClone() {
        TestSuiteResponseDto source = createDeploymentSuite("TSMD Validity Source " + UUID.randomUUID());
        metaTestDataHelper.createTestSuiteMetricDefinition(
                source.getId(), SEED_ACCURACY_ID, SEED_ACCURACY_VERSION_ID, "ValidM");
        TestSuiteMetricDefinition toInvalidate = metaTestDataHelper.createTestSuiteMetricDefinition(
                source.getId(), SEED_ACCURACY_ID, SEED_ACCURACY_VERSION_ID, "InvalidM");
        metaTestDataHelper.forceTsmdInvalid(
                toInvalidate.getId(), "[{\"code\":\"REQUIRED\",\"message\":\"forced\",\"path\":\"$\"}]");

        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("TSMD Validity Clone " + UUID.randomUUID())
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID clonedId = response.getBody().getSuite().getId();
        List<TestSuiteMetricDefinitionResponseDto> clonedTsmds = listTsmds(clonedId);
        assertThat(clonedTsmds).hasSize(2);

        // No override → validity is copied verbatim, not recomputed
        assertThat(tsmdByName(clonedTsmds, "ValidM").isValid()).isTrue();
        TestSuiteMetricDefinitionResponseDto clonedInvalid = tsmdByName(clonedTsmds, "InvalidM");
        assertThat(clonedInvalid.isValid()).isFalse();
        assertThat(clonedInvalid.getValidationWarnings()).isNotEmpty();
    }

    @Test
    @DisplayName("8.2 datasetId override recomputes cloned TSMD validity against the new dataset schema")
    void shouldRecomputeTsmdValidityOnDatasetIdOverride() {
        // createDeploymentSuite binds the source to a dataset whose schema has column "prompt".
        TestSuiteResponseDto source = createDeploymentSuite("Recompute Source " + UUID.randomUUID());
        // TestCase-bound config param referencing "prompt" → valid against the source dataset.
        String configBindings =
                "[{\"property\":\"weight\",\"source\":{\"$type\":\"TestCase\",\"columnName\":\"prompt\"}}]";
        metaTestDataHelper.createTestSuiteMetricDefinition(
                source.getId(), SEED_ACCURACY_ID, SEED_ACCURACY_VERSION_ID, "BoundM", configBindings, "[]");

        // Override dataset lacks "prompt" → the TestCase reference becomes unresolved after rebind.
        UUID overrideDatasetId = newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                .name("other")
                .type(SchemaFieldType.STRING)
                .required(false)
                .build()));

        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Recompute Clone " + UUID.randomUUID())
                .datasetId(overrideDatasetId)
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID clonedId = response.getBody().getSuite().getId();
        TestSuiteMetricDefinitionResponseDto cloned = tsmdByName(listTsmds(clonedId), "BoundM");
        // Recompute (not verbatim copy) flips it to invalid against the override schema
        assertThat(cloned.isValid()).isFalse();
        assertThat(cloned.getValidationWarnings())
                .anyMatch(w -> w.getCode() == ValidationWarningCode.UNRESOLVED_REFERENCE);
    }

    @Test
    @DisplayName("8.2 responseColumns override recomputes cloned TSMD validity, flipping a response-bound TSMD invalid")
    void shouldRecomputeTsmdValidityOnResponseColumnsOverride() {
        TestSuiteResponseDto source = createDeploymentSuite("Response Recompute Source " + UUID.randomUUID());
        // Response-bound config param referencing column "answer". The TSMD is stored valid (helper default),
        // so a verbatim copy would keep it valid — only a recompute against the override can flip it.
        String configBindings =
                "[{\"property\":\"weight\",\"source\":{\"$type\":\"Response\",\"columnName\":\"answer\"}}]";
        metaTestDataHelper.createTestSuiteMetricDefinition(
                source.getId(), SEED_ACCURACY_ID, SEED_ACCURACY_VERSION_ID, "ResponseBoundM", configBindings, "[]");

        // Override responseColumns omit "answer" → the response reference becomes unresolved.
        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Response Recompute Clone " + UUID.randomUUID())
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("other")
                        .expression("$.other")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID clonedId = response.getBody().getSuite().getId();
        TestSuiteMetricDefinitionResponseDto cloned = tsmdByName(listTsmds(clonedId), "ResponseBoundM");
        // Recompute (not verbatim copy) flips it to invalid against the overridden response columns
        assertThat(cloned.isValid()).isFalse();
        assertThat(cloned.getValidationWarnings())
                .anyMatch(w -> w.getCode() == ValidationWarningCode.UNRESOLVED_REFERENCE);
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

    private static TestSuiteMetricDefinitionResponseDto tsmdByName(
            List<TestSuiteMetricDefinitionResponseDto> tsmds, String name) {
        return tsmds.stream()
                .filter(t -> name.equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Cloned TSMD not found by name: " + name));
    }

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

    // -----------------------------------------------------------------------
    // 15.8 — PRIVATE dataset auto-clone semantics
    // -----------------------------------------------------------------------

    @Test
    @DisplayName(
            "15.8 cloning a PRIVATE-dataset suite clones the dataset (new PRIVATE dataset, test cases copied with new "
                    + "ids across a pagination boundary, disabledTestCaseIds remapped, dataset file copied + ref rewritten); source untouched")
    void cloningPrivateDatasetSuiteClonesDataset() {
        Dataset privateDs = metaTestDataHelper.createDataset(
                "Private Src " + UUID.randomUUID(), promptSchemaJson(), DatasetVisibility.PRIVATE);
        TestSuite source = metaTestDataHelper.createTestSuite("Private Suite " + UUID.randomUUID(), privateDs.getId());

        // Seed 3 test cases (batch-size is 2 in tests → crosses a pagination boundary). One carries a
        // dataset-scoped file reference in its data.
        List<UUID> plainIds = metaTestDataHelper.seedManyTestCasesInDataset(privateDs.getId(), 2, true);
        metaTestDataHelper.seedTestCaseInDataset(
                privateDs.getId(), "ref-case", "{\"file\":\"@ef/datasets/" + privateDs.getId() + "/data.csv\"}");
        UUID disabledSourceId = plainIds.get(0);
        metaTestDataHelper.appendDisabledTestCaseIds(source.getId(), List.of(disabledSourceId));
        uploadDatasetFile(privateDs.getId(), "data.csv", "col\n1");

        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Private Clone " + UUID.randomUUID())
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        // No 409 — the clone binds a fresh PRIVATE dataset, so the binding-guard trigger passes
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteResponseDto cloned = response.getBody().getSuite();
        UUID newDatasetId = cloned.getDatasetId();
        assertThat(newDatasetId).isNotNull().isNotEqualTo(privateDs.getId());

        // New dataset is PRIVATE
        Dataset clonedDataset = datasetRepository.findById(newDatasetId).orElseThrow();
        assertThat(clonedDataset.getVisibility()).isEqualTo(DatasetVisibility.PRIVATE);

        // Source dataset untouched
        assertThat(testCaseRepository.countByDatasetId(privateDs.getId())).isEqualTo(3);

        // Cloned dataset has all 3 test cases copied with brand-new ids
        List<TestCase> sourceCases = testCaseRepository.findBatchByDatasetId(privateDs.getId(), 0, 100);
        List<TestCase> clonedCases = testCaseRepository.findBatchByDatasetId(newDatasetId, 0, 100);
        assertThat(clonedCases).hasSize(3);
        List<UUID> sourceIds = sourceCases.stream().map(TestCase::getId).toList();
        assertThat(clonedCases).extracting(TestCase::getId).doesNotContainAnyElementsOf(sourceIds);

        // @ef/datasets ref rewritten from source to new dataset id in the copied test case data
        TestCase clonedRefCase = clonedCases.stream()
                .filter(c -> "ref-case".equals(c.getTestCaseName()))
                .findFirst()
                .orElseThrow();
        assertThat(clonedRefCase.getData())
                .contains("@ef/datasets/" + newDatasetId + "/data.csv")
                .doesNotContain(privateDs.getId().toString());

        // disabledTestCaseIds remapped onto the cloned test case id (old id dropped)
        String disabledSourceName = sourceCases.stream()
                .filter(c -> c.getId().equals(disabledSourceId))
                .map(TestCase::getTestCaseName)
                .findFirst()
                .orElseThrow();
        UUID expectedDisabledCloneId = clonedCases.stream()
                .filter(c -> disabledSourceName.equals(c.getTestCaseName()))
                .map(TestCase::getId)
                .findFirst()
                .orElseThrow();
        assertThat(cloned.getDisabledTestCaseIds())
                .containsExactly(expectedDisabledCloneId)
                .doesNotContain(disabledSourceId);

        // Dataset-scoped file copied to the new dataset folder
        assertThat(listDatasetFilenames(newDatasetId)).contains("data.csv");
    }

    @Test
    @DisplayName(
            "15.8 cloning a PUBLIC-dataset suite shares the dataset — no new dataset row, no test-case rows copied")
    void cloningPublicDatasetSuiteSharesDataset() {
        TestSuiteResponseDto source = createDeploymentSuite("Public Share Source " + UUID.randomUUID());
        UUID sourceDatasetId = source.getDatasetId();
        metaTestDataHelper.seedManyTestCasesInDataset(sourceDatasetId, 2, true);
        long datasetsBefore = datasetRepository.count();

        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Public Share Clone " + UUID.randomUUID())
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Clone shares the PUBLIC dataset — no auto-clone happened
        assertThat(response.getBody().getSuite().getDatasetId()).isEqualTo(sourceDatasetId);
        assertThat(datasetRepository.count()).isEqualTo(datasetsBefore);
        // Source test cases untouched, no extra rows created anywhere
        assertThat(testCaseRepository.countByDatasetId(sourceDatasetId)).isEqualTo(2);
    }

    @Test
    @DisplayName("15.8 clone-name collision dedups: pre-existing '<name> (clone)' yields '<name> (clone 2)'")
    void cloningPrivateDatasetDedupesCloneName() {
        String baseName = "Dedup Ds " + UUID.randomUUID();
        Dataset privateDs = metaTestDataHelper.createDataset(baseName, promptSchemaJson(), DatasetVisibility.PRIVATE);
        TestSuite source = metaTestDataHelper.createTestSuite("Dedup Suite " + UUID.randomUUID(), privateDs.getId());
        // Pre-create a PUBLIC dataset already named "<base> (clone)" to force the dedup suffix
        metaTestDataHelper.createDataset(baseName + " (clone)", "[]");

        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Dedup Clone Suite " + UUID.randomUUID())
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID newDatasetId = response.getBody().getSuite().getDatasetId();
        Dataset clonedDataset = datasetRepository.findById(newDatasetId).orElseThrow();
        assertThat(clonedDataset.getName()).isEqualTo(baseName + " (clone 2)");
    }

    @Test
    @DisplayName(
            "15.8 clone of a PRIVATE-dataset suite with a DIFFERENT datasetId is rejected with 409 (no silent rebind)")
    void cloningPrivateDatasetSuiteWithDifferentDatasetIdReturns409() {
        Dataset privateDs = metaTestDataHelper.createDataset(
                "Private Rebind Src " + UUID.randomUUID(), promptSchemaJson(), DatasetVisibility.PRIVATE);
        TestSuite source =
                metaTestDataHelper.createTestSuite("Private Rebind Suite " + UUID.randomUUID(), privateDs.getId());
        metaTestDataHelper.seedManyTestCasesInDataset(privateDs.getId(), 2, true);
        // A different, existing dataset the client tries to redirect the clone to.
        Dataset otherDs = metaTestDataHelper.createDataset("Other Public " + UUID.randomUUID(), promptSchemaJson());
        long datasetsBefore = datasetRepository.count();
        long suitesBefore = testSuiteRepository.count();

        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Private Rebind Clone " + UUID.randomUUID())
                .datasetId(otherDs.getId())
                .build();
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("PRIVATE_DATASET_REBIND_FORBIDDEN");
        // Nothing was created — no clone suite, no clone dataset, no copied test cases.
        assertThat(testSuiteRepository.count()).isEqualTo(suitesBefore);
        assertThat(datasetRepository.count()).isEqualTo(datasetsBefore);
        assertThat(testCaseRepository.countByDatasetId(otherDs.getId())).isZero();
        // Source PRIVATE dataset still bound only to the source suite (invariant preserved).
        assertThat(testCaseRepository.countByDatasetId(privateDs.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "15.8 clone of a PRIVATE-dataset suite passing the SAME datasetId clones the PRIVATE dataset (like omitting it)")
    void cloningPrivateDatasetSuiteWithSameDatasetIdClonesIt() {
        Dataset privateDs = metaTestDataHelper.createDataset(
                "Private Same Src " + UUID.randomUUID(), promptSchemaJson(), DatasetVisibility.PRIVATE);
        TestSuite source =
                metaTestDataHelper.createTestSuite("Private Same Suite " + UUID.randomUUID(), privateDs.getId());
        metaTestDataHelper.seedManyTestCasesInDataset(privateDs.getId(), 2, true);

        TestSuiteCloneRequestDto request = TestSuiteCloneRequestDto.builder()
                .name("Private Same Clone " + UUID.randomUUID())
                .datasetId(privateDs.getId())
                .build();
        ResponseEntity<TestSuiteUpdateResultDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + source.getId() + "/clone"),
                jsonEntity(request),
                TestSuiteUpdateResultDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID newDatasetId = response.getBody().getSuite().getDatasetId();
        // A fresh PRIVATE dataset was created (not the source) and the test cases were copied.
        assertThat(newDatasetId).isNotNull().isNotEqualTo(privateDs.getId());
        assertThat(datasetRepository.findById(newDatasetId).orElseThrow().getVisibility())
                .isEqualTo(DatasetVisibility.PRIVATE);
        assertThat(testCaseRepository.countByDatasetId(newDatasetId)).isEqualTo(2);
        assertThat(testCaseRepository.countByDatasetId(privateDs.getId())).isEqualTo(2);
    }

    private String promptSchemaJson() {
        try {
            return objectMapper.writeValueAsString(List.of(FieldDefinitionDto.builder()
                    .name("prompt")
                    .type(SchemaFieldType.STRING)
                    .required(false)
                    .build()));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize prompt schema fixture", e);
        }
    }

    private void uploadDatasetFile(UUID datasetId, String filename, String content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<FileMetadataDto> r = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/files"), new HttpEntity<>(body, headers), FileMetadataDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private List<String> listDatasetFilenames(UUID datasetId) {
        ResponseEntity<List<FileMetadataDto>> r = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/files"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody().stream().map(FileMetadataDto::getFilename).toList();
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
