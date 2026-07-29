package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.client.metricprovider.MetricProviderClient;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationRequestDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricOutputFieldDto;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.analytics.repository.MetricScoreResultRepository;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetricDeclarationTestDataProvider;
import com.epam.aidial.evaluation.functional.helper.MetricEvaluationTestHelper;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunReconciliation;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterLocation;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunUpdateDto;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.CustomFunction;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.Mean;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.OverallScoreDefinition;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.WeightedMean;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.WeightedMetric;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestSuiteRun Functional Tests")
public abstract class TestSuiteRunFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private TestSuiteRunReconciliation reconciliation;

    @Autowired
    private DialCoreDeploymentInvoker deploymentInvoker;

    @Autowired
    private MetricProviderClient metricProviderClient;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private MetricDeclarationTestDataProvider metricDeclarationTestDataProvider;

    @Autowired
    private MetricScoreResultRepository metricScoreResultRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            return metaTestDataHelper
                    .createDataset("run-" + UUID.randomUUID(), schemaJson)
                    .getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    // --- Run Creation Tests (Task 32) ---

    @Test
    @DisplayName("Should create a run and receive 202")
    void shouldCreateRun() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Run");

        ResponseEntity<TestSuiteRunResponseDto> response = createRunRequest(suite.getId(), 1, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isNotNull();
        assertThat(response.getBody().getTestSuiteId()).isEqualTo(suite.getId());
        assertThat(response.getBody().getStatus()).isEqualTo(RunStatus.PENDING.name());
        assertThat(response.getBody().getRunConfig()).isNotNull();
        assertThat(response.getBody().getRunConfig().getNumberOfRuns()).isEqualTo(1);
        assertThat(response.getBody().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should return 404 when test suite not found")
    void shouldReturn404WhenTestSuiteNotFound() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + UUID.randomUUID() + "/runs"),
                jsonEntity(buildRunRequest(1, null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 400 when invalid config (missing numberOfRuns)")
    void shouldReturn400WhenInvalidConfig() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Invalid Config");

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().build())
                        .build()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when numberOfRuns exceeds config max")
    void shouldReturn400WhenNumberOfRunsExceedsConfigMax() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Max Runs");

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(buildRunRequest(999, null)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 409 when duplicate test run name")
    void shouldReturn409WhenDuplicateTestRunName() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Dup Name");
        createRunAndAwaitTerminal(suite.getId(), 1, "Unique Run Name");

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(buildRunRequest(1, "Unique Run Name")),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("UNIQUE_CONSTRAINT_VIOLATION");
    }

    @Test
    @DisplayName("Should return 429 when global concurrent run limit exceeded")
    void shouldReturn429WhenGlobalLimitExceeded() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Global Limit");
        List<UUID> runIds = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                runIds.add(metaTestDataHelper
                        .createPendingRun(suite.getId(), "Global Limit Run " + i)
                        .getId());
            }

            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl("/test-suites/" + suite.getId() + "/runs"),
                    jsonEntity(buildRunRequest(1, null)),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getBody()).contains("TOO_MANY_REQUESTS");
        } finally {
            runIds.forEach(id -> metaTestDataHelper.deleteRun(id));
        }
    }

    @Test
    @DisplayName("Should return 429 when per-suite concurrent run limit exceeded")
    void shouldReturn429WhenPerSuiteLimitExceeded() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Suite Limit");
        List<UUID> runIds = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                runIds.add(metaTestDataHelper
                        .createPendingRun(suite.getId(), "Suite Limit Run " + i)
                        .getId());
            }

            ResponseEntity<String> response = restTemplate.postForEntity(
                    apiUrl("/test-suites/" + suite.getId() + "/runs"),
                    jsonEntity(buildRunRequest(1, null)),
                    String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getBody()).contains("TOO_MANY_REQUESTS");
        } finally {
            runIds.forEach(id -> metaTestDataHelper.deleteRun(id));
        }
    }

    @Test
    @DisplayName("Should return 409 when test suite is not in a valid state")
    void shouldReturn409WhenTestSuiteNotValid() {
        TestSuiteResponseDto suite = createTestSuite("Suite Invalid State");
        metaTestDataHelper.forceSuiteInvalid(suite.getId());

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"), jsonEntity(buildRunRequest(1, null)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVALID_OPERATION");
    }

    @Test
    @DisplayName("Should return 409 when running a suite with no test cases")
    void shouldReturn409WhenSuiteHasNoTestCases() {
        TestSuiteResponseDto suite = createTestSuiteWithoutTestCases("Suite No Test Cases");

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"), jsonEntity(buildRunRequest(1, null)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVALID_OPERATION");
    }

    @Test
    @DisplayName("Should accept run after first test case is added to previously empty dataset")
    void shouldAcceptRunAfterTestCaseAdded() {
        TestSuiteResponseDto suite = createTestSuiteWithoutTestCases("Suite Presence Guard");
        ResponseEntity<String> emptyRun = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"), jsonEntity(buildRunRequest(1, null)), String.class);
        assertThat(emptyRun.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(emptyRun.getBody()).contains("INVALID_OPERATION");

        createTestCaseForSuite(suite.getId(), "TC1", Map.of("expected", "answer"));

        ResponseEntity<TestSuiteRunResponseDto> okRun = createRunRequest(suite.getId(), 1, "Run after add");
        assertThat(okRun.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(okRun.getBody()).isNotNull();
        assertThat(okRun.getBody().getNumberOfTestCases()).isEqualTo(1);
    }

    private TestSuiteResponseDto fetchSuite(UUID id) {
        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.getForEntity(apiUrl("/test-suites/" + id), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private TestSuiteResponseDto updateSuiteDisabledTestCaseIds(TestSuiteResponseDto suite, List<UUID> disabledIds) {
        TestSuiteRequestDto request = suiteRequestFrom(suite, suite.getName(), suite.getDatasetId(), disabledIds);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.IF_MATCH, String.valueOf(suite.getVersion()));
        ResponseEntity<TestSuiteResponseDto> response = restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(request, headers),
                TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private TestSuiteRequestDto suiteRequestFrom(
            TestSuiteResponseDto template, String name, UUID datasetId, List<UUID> disabledIds) {
        return TestSuiteRequestDto.builder()
                .name(name)
                .description(template.getDescription())
                .suiteType(template.getSuiteType())
                .datasetId(datasetId)
                .disabledTestCaseIds(disabledIds)
                .deploymentRef(template.getDeploymentRef())
                .endpointRef(template.getEndpointRef())
                .responseColumns(template.getResponseColumns())
                .requestTemplate(template.getRequestTemplate())
                .inputBindings(template.getInputBindings())
                .build();
    }

    // --- Run CRUD Tests (Task 33) ---

    @Test
    @DisplayName("Should get run by ID")
    void shouldGetRunById() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Get");
        TestSuiteRunResponseDto created = createRunAndAwaitTerminal(suite.getId(), 1, null);

        ResponseEntity<TestSuiteRunResponseDto> response =
                restTemplate.getForEntity(apiUrl("/test-suite-runs/" + created.getId()), TestSuiteRunResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo(created.getId());
    }

    @Test
    @DisplayName("Should return 404 when run not found")
    void shouldReturn404WhenRunNotFound() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(apiUrl("/test-suite-runs/" + UUID.randomUUID()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should list runs")
    void shouldListRuns() {
        TestSuiteResponseDto suite = createTestSuite("Suite For List");
        createRunAndAwaitTerminal(suite.getId(), 1, "List Run 1");
        createRunAndAwaitTerminal(suite.getId(), 1, "List Run 2");

        ResponseEntity<PageResponseDto<TestSuiteRunResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suite-runs?page=0&size=100"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Should list runs with filters")
    void shouldListRunsWithFilters() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Filter");
        createRunAndAwaitTerminal(suite.getId(), 1, null);

        ResponseEntity<PageResponseDto<TestSuiteRunResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suite-runs?page=0&size=100&filter=testSuiteId:eq:" + suite.getId()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).isNotEmpty();
        assertThat(response.getBody().getContent())
                .allMatch(r -> r.getTestSuiteId().equals(suite.getId()));
    }

    @Test
    @DisplayName("Should filter runs by id equality")
    void shouldFilterRunsById() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Filter By Id");
        TestSuiteRunResponseDto run1 = createRunAndAwaitTerminal(suite.getId(), 1, null);
        createRunAndAwaitTerminal(suite.getId(), 1, null);

        ResponseEntity<PageResponseDto<TestSuiteRunResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suite-runs?page=0&size=100&filter=id:eq:" + run1.getId()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        assertThat(response.getBody().getContent().get(0).getId()).isEqualTo(run1.getId());
    }

    @Test
    @DisplayName("Should filter runs by id set membership")
    void shouldFilterRunsByIdIn() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Filter By Id In");
        TestSuiteRunResponseDto run1 = createRunAndAwaitTerminal(suite.getId(), 1, null);
        TestSuiteRunResponseDto run2 = createRunAndAwaitTerminal(suite.getId(), 1, null);
        createRunAndAwaitTerminal(suite.getId(), 1, null);

        ResponseEntity<PageResponseDto<TestSuiteRunResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suite-runs?page=0&size=100&filter=id:in:" + run1.getId() + "," + run2.getId()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(2);
        assertThat(response.getBody().getContent())
                .extracting(TestSuiteRunResponseDto::getId)
                .containsExactlyInAnyOrder(run1.getId(), run2.getId());
    }

    @Test
    @DisplayName("Should filter runs by startedAt range")
    void shouldFilterRunsByStartedAt() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Filter By StartedAt");
        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 1, null);

        assertThat(run.getStartedAt()).isNotNull();
        long filterValue = run.getStartedAt() - 1;

        ResponseEntity<PageResponseDto<TestSuiteRunResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suite-runs?page=0&size=100&filter=startedAt:ge:" + filterValue + "&filter=id:eq:"
                        + run.getId()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        assertThat(response.getBody().getContent().get(0).getId()).isEqualTo(run.getId());
    }

    @Test
    @DisplayName("Should filter runs by completedAt range")
    void shouldFilterRunsByCompletedAt() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Filter By CompletedAt");
        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 1, null);

        assertThat(run.getCompletedAt()).isNotNull();
        long filterValue = run.getCompletedAt() + 1;

        ResponseEntity<PageResponseDto<TestSuiteRunResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suite-runs?page=0&size=100&filter=completedAt:le:" + filterValue + "&filter=id:eq:"
                        + run.getId()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        assertThat(response.getBody().getContent().get(0).getId()).isEqualTo(run.getId());
    }

    @Test
    @DisplayName("Should accept deprecated gte/lte aliases and return same result set as ge/le")
    void shouldAcceptDeprecatedGteLteAliases() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Alias Smoke");
        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 1, null);
        long startedAtBound = run.getStartedAt() - 1;
        long completedAtBound = run.getCompletedAt() + 1;

        ResponseEntity<PageResponseDto<TestSuiteRunResponseDto>> canonical = restTemplate.exchange(
                apiUrl("/test-suite-runs?page=0&size=100"
                        + "&filter=startedAt:ge:" + startedAtBound
                        + "&filter=completedAt:le:" + completedAtBound
                        + "&filter=id:eq:" + run.getId()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        ResponseEntity<PageResponseDto<TestSuiteRunResponseDto>> aliased = restTemplate.exchange(
                apiUrl("/test-suite-runs?page=0&size=100"
                        + "&filter=startedAt:gte:" + startedAtBound
                        + "&filter=completedAt:lte:" + completedAtBound
                        + "&filter=id:eq:" + run.getId()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(canonical.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(aliased.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(canonical.getBody()).isNotNull();
        assertThat(aliased.getBody()).isNotNull();
        assertThat(canonical.getBody().getContent())
                .extracting(TestSuiteRunResponseDto::getId)
                .containsExactly(run.getId());
        assertThat(aliased.getBody().getContent())
                .extracting(TestSuiteRunResponseDto::getId)
                .containsExactlyElementsOf(canonical.getBody().getContent().stream()
                        .map(TestSuiteRunResponseDto::getId)
                        .toList());
    }

    @Test
    @DisplayName("Should list runs with sorting")
    void shouldListRunsWithSorting() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Sort");
        createRunAndAwaitTerminal(suite.getId(), 1, "A Run");
        createRunAndAwaitTerminal(suite.getId(), 1, "B Run");

        ResponseEntity<PageResponseDto<TestSuiteRunResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suite-runs?page=0&size=100&sort=testRunName,asc" + "&filter=testSuiteId:eq:"
                        + suite.getId()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        List<TestSuiteRunResponseDto> content = response.getBody().getContent();
        assertThat(content).hasSizeGreaterThanOrEqualTo(2);
        assertThat(content.get(0).getTestRunName()).isLessThan(content.get(1).getTestRunName());
    }

    @Test
    @DisplayName("Should list runs with includeTotalCount")
    void shouldListRunsWithIncludeTotalCount() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Total");
        createRunAndAwaitTerminal(suite.getId(), 1, null);

        ResponseEntity<PageResponseDto<TestSuiteRunResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suite-runs?page=0&size=100&includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalElements()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("Should update test run name")
    void shouldUpdateTestRunName() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Update");
        TestSuiteRunResponseDto created = createRunAndAwaitTerminal(suite.getId(), 1, "Original");

        ResponseEntity<TestSuiteRunResponseDto> response = restTemplate.exchange(
                apiUrl("/test-suite-runs/" + created.getId()),
                HttpMethod.PATCH,
                jsonEntity(
                        TestSuiteRunUpdateDto.builder().testRunName("Updated").build()),
                TestSuiteRunResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTestRunName()).isEqualTo("Updated");
    }

    @Test
    @DisplayName("Should return 409 when update duplicate test run name")
    void shouldReturn409WhenUpdateDuplicateTestRunName() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Dup Update");
        createRunAndAwaitTerminal(suite.getId(), 1, "Name A");
        TestSuiteRunResponseDto runB = createRunAndAwaitTerminal(suite.getId(), 1, "Name B");

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/test-suite-runs/" + runB.getId()),
                HttpMethod.PATCH,
                jsonEntity(TestSuiteRunUpdateDto.builder().testRunName("Name A").build()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Should delete terminal run")
    void shouldDeleteTerminalRun() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Delete");
        TestSuiteRunResponseDto created = createRunAndAwaitTerminal(suite.getId(), 1, null);

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                apiUrl("/test-suite-runs/" + created.getId()), HttpMethod.DELETE, null, Void.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> getResponse =
                restTemplate.getForEntity(apiUrl("/test-suite-runs/" + created.getId()), String.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 409 when delete non-terminal run")
    void shouldReturn409WhenDeleteNonTerminal() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Del NonTerm");
        // Insert a RUNNING run directly to avoid it becoming terminal
        UUID runId =
                metaTestDataHelper.createRunningRun(suite.getId(), "Stuck Run").getId();

        ResponseEntity<String> response =
                restTemplate.exchange(apiUrl("/test-suite-runs/" + runId), HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVALID_OPERATION");
    }

    // --- Cancel Tests (Task 34) ---

    @Test
    @DisplayName("Should cancel pending run")
    void shouldCancelPendingRun() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Cancel Pending");
        // Insert a PENDING run directly so it stays PENDING
        UUID runId = metaTestDataHelper
                .createPendingRun(suite.getId(), "Pending Cancel")
                .getId();

        ResponseEntity<TestSuiteRunResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suite-runs/" + runId + "/cancel"), null, TestSuiteRunResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(RunStatus.CANCELLED.name());
    }

    @Test
    @DisplayName("Should cancel running run")
    void shouldCancelRunningRun() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Cancel Running");
        // Insert RUNNING run directly
        UUID runId = metaTestDataHelper
                .createRunningRun(suite.getId(), "Running Cancel")
                .getId();

        ResponseEntity<TestSuiteRunResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suite-runs/" + runId + "/cancel"), null, TestSuiteRunResponseDto.class);

        // For RUNNING, cancel triggers interrupt; response returns current state
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Should return 409 when cancel terminal run")
    void shouldReturn409WhenCancelTerminalRun() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Cancel Terminal");
        TestSuiteRunResponseDto completed = createRunAndAwaitTerminal(suite.getId(), 1, null);

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suite-runs/" + completed.getId() + "/cancel"), null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("INVALID_OPERATION");
    }

    // --- Reconciliation Test (Task 35) ---

    @Test
    @DisplayName("Should mark orphaned runs as FAILED on reconciliation")
    void shouldMarkOrphanedRunsAsFailedOnReconciliation() {
        TestSuiteResponseDto suite = createTestSuite("Suite For Recon");
        UUID pendingId = metaTestDataHelper
                .createPendingRun(suite.getId(), "Orphan Pending")
                .getId();
        UUID runningId = metaTestDataHelper
                .createRunningRun(suite.getId(), "Orphan Running")
                .getId();

        reconciliation.reconcileOrphanedRuns();

        ResponseEntity<TestSuiteRunResponseDto> pending =
                restTemplate.getForEntity(apiUrl("/test-suite-runs/" + pendingId), TestSuiteRunResponseDto.class);
        ResponseEntity<TestSuiteRunResponseDto> running =
                restTemplate.getForEntity(apiUrl("/test-suite-runs/" + runningId), TestSuiteRunResponseDto.class);

        assertThat(pending.getBody()).isNotNull();
        assertThat(pending.getBody().getStatus()).isEqualTo(RunStatus.FAILED.name());
        assertThat(running.getBody()).isNotNull();
        assertThat(running.getBody().getStatus()).isEqualTo(RunStatus.FAILED.name());
    }

    // --- SSE Tests (Task 36) ---

    @Test
    @DisplayName("Should connect to status stream and receive events")
    void shouldConnectToStatusStream() throws Exception {
        // Use java.net.HttpURLConnection for SSE since TestRestTemplate waits for response completion
        java.net.URL url =
                java.net.URI.create(apiUrl("/test-suite-runs/status-stream")).toURL();
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);

        try {
            assertThat(conn.getResponseCode()).isEqualTo(200);
            assertThat(conn.getContentType()).startsWith("text/event-stream");
        } finally {
            conn.disconnect();
        }
    }

    // --- Metric Evaluation Tests ---

    @Test
    @DisplayName("Should complete two-phase run producing eval summaries and run metric snapshots")
    void shouldCompleteTwoPhaseRunWithMetricEvaluation() {
        // Setup: suite with response column for extraction
        TestSuiteResponseDto suite = createTestSuiteWithResponseColumn("Suite For Metric Eval");
        createTestCaseForSuite(suite.getId(), "TC1", Map.of("expected", "answer1"));
        createTestCaseForSuite(suite.getId(), "TC2", Map.of("expected", "answer2"));

        // Create metric declaration + version
        metricDeclarationTestDataProvider.insertSeedMetricDeclarations();
        metricDeclarationTestDataProvider.insertSeedVersionForAccuracy();
        UUID declarationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID versionId = UUID.fromString("770e8400-e29b-41d4-a716-446655440001");

        // Create TSMD with input binding referencing extracted column
        String inputBindings = """
                [{"property": "actual", "source": {"$type": "Response", "columnName": "answer"}}]
                """;
        metaTestDataHelper.createTestSuiteMetricDefinition(
                suite.getId(), declarationId, versionId, "Accuracy", "[]", inputBindings.trim());

        // Configure mocks: first deployment succeeds, second fails
        new MetricEvaluationTestHelper(deploymentInvoker, metricProviderClient)
                .withDeploymentSuccess(Map.of(
                        "id",
                        "mock-chatcmpl-1",
                        "choices",
                        List.of(Map.of("message", Map.of("content", "Mocked answer.")))))
                .withDeploymentFailureOnCall(2, new ResourceAccessException("Connection timed out"))
                .withMetricResponse(
                        "Accuracy",
                        EvaluationResponseDto.builder()
                                .metricName("Accuracy")
                                .output(Map.of(
                                        "Accuracy",
                                        MetricOutputFieldDto.builder()
                                                .type("value")
                                                .value(BigDecimal.ONE)
                                                .build()))
                                .build())
                .apply();

        // Execute and await terminal
        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 1, null);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // Assert: eval summaries exist (one per test case)
        List<Map<String, Object>> evalSummaries = analyticsTestDataHelper.findEvalSummariesByRunId(run.getId());
        assertThat(evalSummaries).hasSize(2);

        // One SUCCESS (metric eval ran), one non-SUCCESS (propagated deployment failure)
        long successCount = evalSummaries.stream()
                .filter(s -> "SUCCESS".equals(s.get("execution_status")))
                .count();
        long nonSuccessCount = evalSummaries.stream()
                .filter(s -> !"SUCCESS".equals(s.get("execution_status")))
                .count();
        assertThat(successCount).isEqualTo(1);
        assertThat(nonSuccessCount).isEqualTo(1);

        // SUCCESS summary has populated metricValues
        Map<String, Object> successSummary = evalSummaries.stream()
                .filter(s -> "SUCCESS".equals(s.get("execution_status")))
                .findFirst()
                .orElseThrow();
        String metricValues = (String) successSummary.get("metric_values");
        assertThat(metricValues).contains("Accuracy");

        // Non-SUCCESS summary has empty metricValues
        Map<String, Object> failedSummary = evalSummaries.stream()
                .filter(s -> !"SUCCESS".equals(s.get("execution_status")))
                .findFirst()
                .orElseThrow();
        assertThat((String) failedSummary.get("metric_values")).isEqualTo("{}");

        // Assert: run metric snapshots exist
        List<Map<String, Object>> snapshots = analyticsTestDataHelper.findRunMetricSnapshotsByRunId(run.getId());
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).get("tsmd_name")).isEqualTo("Accuracy");

        // All summaries and snapshots share the same computation_id
        String computationId = (String) snapshots.get(0).get("computation_id");
        assertThat(computationId).isNotNull();
        assertThat(evalSummaries).allMatch(s -> computationId.equals(s.get("computation_id")));
    }

    @Test
    @DisplayName("Should write metric-less eval summaries when no TSMDs configured")
    void shouldWriteMetricLessEvalSummariesWhenNoTsmds() {
        TestSuiteResponseDto suite = createTestSuiteWithResponseColumn("Suite No Metrics");
        createTestCaseForSuite(suite.getId(), "TC1", Map.of("expected", "test"));

        // Mock deployment (no metric mocks needed)
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(new DeploymentInvocationResult(
                        200,
                        false,
                        Map.of("id", "mock", "choices", List.of(Map.of("message", Map.of("content", "answer")))),
                        null,
                        new HttpHeaders()));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 1, null);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // One metric-less eval summary per result row: empty metric_values, no metric_infos.
        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        List<Map<String, Object>> summaries = analyticsTestDataHelper.findEvalSummariesByRunId(run.getId());
        assertThat(results).isNotEmpty();
        assertThat(summaries).hasSameSizeAs(results);
        assertThat(summaries).allSatisfy(summary -> {
            assertThat((String) summary.get("metric_values")).isEqualTo("{}");
            assertThat(summary.get("metric_infos")).isNull();
        });

        // No metrics ⇒ no run metric snapshots and no Phase-3 metric scores.
        assertThat(analyticsTestDataHelper.findRunMetricSnapshotsByRunId(run.getId()))
                .isEmpty();
        UUID computationId = UUID.fromString((String) summaries.get(0).get("computation_id"));
        assertThat(metricScoreResultRepository.findByRunAndComputation(run.getId(), computationId))
                .isEmpty();
    }

    @Test
    @DisplayName("Should resolve ARRAY/OBJECT test case columns as structured values in metric evaluation")
    void shouldResolveComplexTypesInMetricEvaluation() {
        // Setup: suite with ARRAY + OBJECT columns and a response column
        TestSuiteResponseDto suite = createTestSuiteWithComplexSchema("Suite Complex Types Eval");

        // Create test case with array and object data
        createTestCaseForSuite(
                suite.getId(),
                "TC1",
                Map.of(
                        "expected", "correct",
                        "tags", List.of("a", "b", "c"),
                        "config", Map.of("model", "gpt-4", "temperature", 0.7)));

        // Create metric declaration + version
        metricDeclarationTestDataProvider.insertSeedMetricDeclarations();
        metricDeclarationTestDataProvider.insertSeedVersionForAccuracy();
        UUID declarationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID versionId = UUID.fromString("770e8400-e29b-41d4-a716-446655440001");

        // Create TSMD with bindings: testcase-column (tags, config) + constant + response column
        String inputBindings = """
                [
                  {"property": "actual", "source": {"$type": "Response", "columnName": "answer"}},
                  {"property": "tags_input", "source": {"$type": "TestCase", "columnName": "tags"}},
                  {"property": "config_input", "source": {"$type": "TestCase", "columnName": "config"}},
                  {"property": "threshold", "source": {"$type": "Constant", "value": 0.8}}
                ]
                """;
        metaTestDataHelper.createTestSuiteMetricDefinition(
                suite.getId(), declarationId, versionId, "Accuracy", "[]", inputBindings.trim());

        // Capture metric provider requests to assert complex types
        List<EvaluationRequestDto> capturedRequests = new ArrayList<>();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(new DeploymentInvocationResult(
                        200,
                        false,
                        Map.of(
                                "id",
                                "mock-1",
                                "choices",
                                List.of(Map.of("message", Map.of("content", "Mocked answer.")))),
                        null,
                        new HttpHeaders()));
        when(metricProviderClient.evaluate(anyString(), any(EvaluationRequestDto.class)))
                .thenAnswer(invocation -> {
                    EvaluationRequestDto request = invocation.getArgument(1);
                    capturedRequests.add(request);
                    return EvaluationResponseDto.builder()
                            .metricName("Accuracy")
                            .output(Map.of(
                                    "Accuracy",
                                    MetricOutputFieldDto.builder()
                                            .type("value")
                                            .value(BigDecimal.ONE)
                                            .build()))
                            .build();
                });

        // Execute run and await terminal
        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 1, null);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // Assert: metric provider received the request
        assertThat(capturedRequests).hasSize(1);
        EvaluationRequestDto captured = capturedRequests.get(0);

        // Assert: tags_input is a List (not a String)
        assertThat(captured.getInput().get("tags_input"))
                .isInstanceOf(List.class)
                .isEqualTo(List.of("a", "b", "c"));

        // Assert: config_input is a Map (not a String)
        assertThat(captured.getInput().get("config_input")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> configInput =
                (Map<String, Object>) captured.getInput().get("config_input");
        assertThat(configInput).containsEntry("model", "gpt-4");
        assertThat(configInput).containsEntry("temperature", 0.7);

        // Assert: constant binding resolved correctly
        assertThat(captured.getInput().get("threshold")).isEqualTo(0.8);

        // Assert: response column binding resolved correctly
        assertThat(captured.getInput().get("actual")).isEqualTo("Mocked answer.");

        // Assert: eval summary exists and is SUCCESS
        List<Map<String, Object>> evalSummaries = analyticsTestDataHelper.findEvalSummariesByRunId(run.getId());
        assertThat(evalSummaries).hasSize(1);
        assertThat(evalSummaries.get(0).get("execution_status")).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("Should compute a suite's custom overall roc_auc score over a dataset label and a metric probability")
    void shouldComputeCustomOverallRocAucFromDatasetLabelAndMetricProbability() {
        // roc_auc(data::y, metric::Classifier::probability), stored as the suite's overallScore.
        CustomFunction overallScore = new CustomFunction(Map.of(
                "entity",
                "eval_summaries",
                "mode",
                "aggregate",
                "filter",
                Map.of(
                        "op",
                        "and",
                        "args",
                        List.of(
                                Map.of(
                                        "op",
                                        "eq",
                                        "args",
                                        List.of(
                                                Map.of("type", "field", "name", "test_suite_run_id"),
                                                Map.of("type", "param", "name", "runId"))),
                                Map.of(
                                        "op",
                                        "eq",
                                        "args",
                                        List.of(
                                                Map.of("type", "field", "name", "computation_id"),
                                                Map.of("type", "param", "name", "computationId"))))),
                "select",
                List.of(Map.of(
                        "expr",
                        Map.of(
                                "type",
                                "fn",
                                "name",
                                "roc_auc",
                                "args",
                                List.of(
                                        Map.of("type", "field", "name", "data::y"),
                                        Map.of("type", "field", "name", "metric::Classifier::probability"))),
                        "as",
                        "value"))));

        TestSuiteResponseDto suite = createTestSuiteWithOverallScore("Suite For ROC AUC Overall", overallScore);

        // label/probabilityHint pairs: (0, 0.1), (0, 0.4), (1, 0.35), (1, 0.8) -> one discordant pair -> AUC = 0.75.
        createTestCaseForSuite(suite.getId(), "case-a", Map.of("y", 0, "probabilityHint", 0.1));
        createTestCaseForSuite(suite.getId(), "case-b", Map.of("y", 0, "probabilityHint", 0.4));
        createTestCaseForSuite(suite.getId(), "case-c", Map.of("y", 1, "probabilityHint", 0.35));
        createTestCaseForSuite(suite.getId(), "case-d", Map.of("y", 1, "probabilityHint", 0.8));

        // Classifier metric declaration + version whose output is a single "probability" field.
        metricDeclarationTestDataProvider.insertSeedMetricDeclarations();
        String classifierVersionId = UUID.randomUUID().toString();
        metricDeclarationTestDataProvider.insertVersionWithSchemas(
                classifierVersionId,
                "00000000-0000-0000-0000-000000000001",
                1,
                "{}",
                "{}",
                "{\"properties\":{\"probability\":{\"type\":\"number\"}}}");

        // TSMD binds the dataset's probabilityHint column into the metric input; the mock echoes it
        // back as the metric's "probability" output, simulating a classifier scoring each test case.
        String inputBindings = """
                [{"property": "phint", "source": {"$type": "TestCase", "columnName": "probabilityHint"}}]
                """;
        metaTestDataHelper.createTestSuiteMetricDefinition(
                suite.getId(),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString(classifierVersionId),
                "Classifier",
                "[]",
                inputBindings.trim());

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(new DeploymentInvocationResult(
                        200,
                        false,
                        Map.of("id", "mock", "choices", List.of(Map.of("message", Map.of("content", "answer")))),
                        null,
                        new HttpHeaders()));
        when(metricProviderClient.evaluate(anyString(), any(EvaluationRequestDto.class)))
                .thenAnswer(invocation -> {
                    EvaluationRequestDto request = invocation.getArgument(1);
                    BigDecimal probability =
                            new BigDecimal(request.getInput().get("phint").toString());
                    return EvaluationResponseDto.builder()
                            .metricName("Classifier")
                            .output(Map.of(
                                    "probability",
                                    MetricOutputFieldDto.builder()
                                            .type("value")
                                            .value(probability)
                                            .build()))
                            .build();
                });

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 1, null);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> snapshots = analyticsTestDataHelper.findRunMetricSnapshotsByRunId(run.getId());
        assertThat(snapshots).hasSize(1);
        UUID computationId = UUID.fromString((String) snapshots.get(0).get("computation_id"));

        List<MetricScoreResult> results =
                metricScoreResultRepository.findByRunAndComputation(run.getId(), computationId);
        MetricScoreResult overall = results.stream()
                .filter(r -> "overall".equals(r.getMetricScoreName()) && "overall".equals(r.getMetricName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing overall metric score result"));
        assertThat(overall.getValue()).isCloseTo(0.75, within(1e-9));
    }

    @Test
    @DisplayName("Should compute a suite's custom overall as a weighted mean Sigma(w_i*m_i)/Sigma(w_i) of two metrics, "
            + "combining a duplicated weighted term")
    void shouldComputeCustomOverallWeightedMeanOfSpecificMetrics() {
        // Sigma(w_i*m_i)/Sigma(w_i), stored as the suite's overallScore. Weights already sum to 1 (as they
        // would once normalized), and both metrics are in [0, 1], so the resulting overall score is <= 1.
        WeightedMean overallScore = new WeightedMean(List.of(
                new WeightedMetric("MetricA", "score", new BigDecimal("0.1")),
                new WeightedMetric("MetricA", "score", new BigDecimal("0.1")),
                new WeightedMetric("MetricB", "score", new BigDecimal("0.8"))));

        TestSuiteResponseDto suite = createSuiteWithTwoWeightableMetrics(
                "Suite For Weighted Mean Overall",
                overallScore,
                Map.of("valA", 0.1, "valB", 0.6),
                Map.of("valA", 0.3, "valB", 1.0));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 1, null);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // avg(MetricA) = avg(0.1, 0.3) = 0.2, avg(MetricB) = avg(0.6, 1.0) = 0.8.
        // weighted mean = (0.1*0.2 + 0.1*0.2 + 0.8*0.8) / (0.1 + 0.1 + 0.8) = 0.68 / 1.0 = 0.68.
        assertThat(fetchOverallResult(run).getValue()).isCloseTo(0.68, within(1e-9));
    }

    @Test
    @DisplayName("Should compute a suite's custom overall as the unweighted mean of all metric output via mean(...)")
    void shouldComputeCustomOverallMeanOfAllMetricOutput() {
        TestSuiteResponseDto suite = createSuiteWithTwoWeightableMetrics(
                "Suite For Mean Overall",
                new Mean(),
                Map.of("valA", 0.1, "valB", 0.6),
                Map.of("valA", 0.3, "valB", 1.0));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 1, null);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // avg(MetricA) = avg(0.1, 0.3) = 0.2, avg(MetricB) = avg(0.6, 1.0) = 0.8. mean = (0.2 + 0.8) / 2 = 0.5.
        assertThat(fetchOverallResult(run).getValue()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    @DisplayName("Should normalize unnormalized integer weights via Sigma(w_i)-division, including a genuine "
            + "repeating-decimal (1/3) case")
    void shouldNormalizeUnroundedWeightsInWeightedMeanOverallScore() {
        // divide(add(multiply(1, avg(MetricA)), multiply(1, avg(MetricB)), multiply(1, avg(MetricB))),
        //        add(1, 1, 1)), stored as the suite's overallScore. Weights are plain, unnormalized integers
        // (Sigma(w) = 3, not 1) -- the caller does NOT pre-normalize them to decimals like 0.33/0.33/0.34.
        // With avg(MetricA) = 1 and avg(MetricB) = 0, the weighted sum is 1 and the division is a genuine
        // repeating decimal (1 / 3 = 0.333...). Postgres's numeric division resolves this without error --
        // unlike plain Java BigDecimal.divide(BigDecimal) (no MathContext), which throws
        // ArithmeticException: Non-terminating decimal expansion for this exact case -- because the
        // division is compiled into SQL and executed by Postgres at query time, not evaluated in Java.
        WeightedMean overallScore = new WeightedMean(List.of(
                new WeightedMetric("MetricA", "score", new BigDecimal("1")),
                new WeightedMetric("MetricB", "score", new BigDecimal("1")),
                new WeightedMetric("MetricB", "score", new BigDecimal("1"))));

        TestSuiteResponseDto suite = createSuiteWithTwoWeightableMetrics(
                "Suite For Unnormalized Weights Overall",
                overallScore,
                Map.of("valA", 1.0, "valB", 0.0),
                Map.of("valA", 1.0, "valB", 0.0));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 1, null);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // avg(MetricA) = 1, avg(MetricB) = 0. weighted mean = (1*1 + 1*0 + 1*0) / (1 + 1 + 1) = 1/3.
        assertThat(fetchOverallResult(run).getValue()).isCloseTo(1.0 / 3.0, within(1e-9));
    }

    /**
     * A suite bound to two numeric fields ({@code valA}, {@code valB}), two test cases (data supplied by
     * the caller), and two metrics (MetricA reading {@code valA}, MetricB reading {@code valB}, each
     * outputting a numeric {@code score}) — the shared fixture for the weighted-mean and mean
     * overall-score tests.
     */
    private TestSuiteResponseDto createSuiteWithTwoWeightableMetrics(
            String suiteName,
            OverallScoreDefinition overallScore,
            Map<String, Object> testCaseDataA,
            Map<String, Object> testCaseDataB) {
        TestSuiteResponseDto suite = createTestSuiteWithTwoNumericFieldsOverallScore(suiteName, overallScore);

        createTestCaseForSuite(suite.getId(), "case-a", testCaseDataA);
        createTestCaseForSuite(suite.getId(), "case-b", testCaseDataB);

        metricDeclarationTestDataProvider.insertSeedMetricDeclarations();
        String metricVersionA = UUID.randomUUID().toString();
        String metricVersionB = UUID.randomUUID().toString();
        metricDeclarationTestDataProvider.insertVersionWithSchemas(
                metricVersionA,
                "00000000-0000-0000-0000-000000000001",
                1,
                "{}",
                "{}",
                "{\"properties\":{\"score\":{\"type\":\"number\"}}}");
        metricDeclarationTestDataProvider.insertVersionWithSchemas(
                metricVersionB,
                "00000000-0000-0000-0000-000000000002",
                1,
                "{}",
                "{}",
                "{\"properties\":{\"score\":{\"type\":\"number\"}}}");

        metaTestDataHelper.createTestSuiteMetricDefinition(
                suite.getId(),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString(metricVersionA),
                "MetricA",
                "[]",
                "[{\"property\": \"value\", \"source\": {\"$type\": \"TestCase\", \"columnName\": \"valA\"}}]");
        metaTestDataHelper.createTestSuiteMetricDefinition(
                suite.getId(),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString(metricVersionB),
                "MetricB",
                "[]",
                "[{\"property\": \"value\", \"source\": {\"$type\": \"TestCase\", \"columnName\": \"valB\"}}]");

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(new DeploymentInvocationResult(
                        200,
                        false,
                        Map.of("id", "mock", "choices", List.of(Map.of("message", Map.of("content", "answer")))),
                        null,
                        new HttpHeaders()));
        when(metricProviderClient.evaluate(anyString(), any(EvaluationRequestDto.class)))
                .thenAnswer(invocation -> {
                    EvaluationRequestDto request = invocation.getArgument(1);
                    BigDecimal value =
                            new BigDecimal(request.getInput().get("value").toString());
                    return EvaluationResponseDto.builder()
                            .metricName(request.getMetricName())
                            .output(Map.of(
                                    "score",
                                    MetricOutputFieldDto.builder()
                                            .type("value")
                                            .value(value)
                                            .build()))
                            .build();
                });

        return suite;
    }

    private MetricScoreResult fetchOverallResult(TestSuiteRunResponseDto run) {
        List<Map<String, Object>> snapshots = analyticsTestDataHelper.findRunMetricSnapshotsByRunId(run.getId());
        assertThat(snapshots).hasSize(2);
        UUID computationId = UUID.fromString((String) snapshots.get(0).get("computation_id"));

        List<MetricScoreResult> results =
                metricScoreResultRepository.findByRunAndComputation(run.getId(), computationId);
        return results.stream()
                .filter(r -> "overall".equals(r.getMetricScoreName()) && "overall".equals(r.getMetricName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing overall metric score result"));
    }

    // --- Helper Methods ---

    private TestSuiteRunResponseDto createRunAndAwaitTerminal(UUID testSuiteId, int numberOfRuns, String name) {
        ResponseEntity<TestSuiteRunResponseDto> response = createRunRequest(testSuiteId, numberOfRuns, name);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        return awaitRunTerminal(response.getBody().getId(), 15);
    }

    private ResponseEntity<TestSuiteRunResponseDto> createRunRequest(UUID testSuiteId, int numberOfRuns, String name) {
        return restTemplate.postForEntity(
                apiUrl("/test-suites/" + testSuiteId + "/runs"),
                jsonEntity(buildRunRequest(numberOfRuns, name)),
                TestSuiteRunResponseDto.class);
    }

    private TestSuiteRunRequestDto buildRunRequest(int numberOfRuns, String testRunName) {
        return TestSuiteRunRequestDto.builder()
                .runConfig(RunConfigDto.builder()
                        .numberOfRuns(numberOfRuns)
                        .testRunName(testRunName)
                        .build())
                .build();
    }

    private TestSuiteRunResponseDto awaitRunTerminal(UUID runId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<TestSuiteRunResponseDto> get =
                    restTemplate.getForEntity(apiUrl("/test-suite-runs/" + runId), TestSuiteRunResponseDto.class);
            if (get.getStatusCode() == HttpStatus.OK
                    && get.getBody() != null
                    && RunStatus.isTerminal(get.getBody().getStatus())) {
                return get.getBody();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while polling run", e);
            }
        }
        throw new AssertionError("Run did not reach terminal status within " + timeoutSeconds + "s");
    }

    private TestSuiteResponseDto createTestSuite(String name) {
        TestSuiteResponseDto suite = createTestSuiteWithoutTestCases(name);
        createTestCaseForSuite(suite.getId(), "Default TC", Map.of("expected", "value"));
        return suite;
    }

    private TestSuiteResponseDto createTestSuiteWithoutTestCases(String name) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name)
                .description("Description for " + name)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .parameters(List.of(ParameterDefinitionDto.builder()
                                .name("query")
                                .in(ParameterLocation.QUERY)
                                .required(true)
                                .schema(Map.of("type", "string"))
                                .build()))
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of(
                                        "type", "object",
                                        "required", List.of("prompt"),
                                        "properties", Map.of("prompt", Map.of("type", "string"))))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TestSuiteResponseDto createTestSuiteWithResponseColumn(String name) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name)
                .description("Description for " + name)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .parameters(List.of(ParameterDefinitionDto.builder()
                                .name("q")
                                .in(ParameterLocation.QUERY)
                                .required(false)
                                .schema(Map.of("type", "string"))
                                .build()))
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("choices[0].message.content")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TestSuiteResponseDto createTestSuiteWithOverallScore(String name, OverallScoreDefinition overallScore) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name)
                .description("Description for " + name)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .parameters(List.of(ParameterDefinitionDto.builder()
                                .name("query")
                                .in(ParameterLocation.QUERY)
                                .required(true)
                                .schema(Map.of("type", "string"))
                                .build()))
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of(
                                        "type", "object",
                                        "required", List.of("prompt"),
                                        "properties", Map.of("prompt", Map.of("type", "string"))))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(
                        FieldDefinitionDto.builder()
                                .name("y")
                                .type(SchemaFieldType.NUMBER)
                                .required(true)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("probabilityHint")
                                .type(SchemaFieldType.NUMBER)
                                .required(true)
                                .build())))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .overallScore(overallScore)
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TestSuiteResponseDto createTestSuiteWithTwoNumericFieldsOverallScore(
            String name, OverallScoreDefinition overallScore) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name)
                .description("Description for " + name)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .parameters(List.of(ParameterDefinitionDto.builder()
                                .name("query")
                                .in(ParameterLocation.QUERY)
                                .required(true)
                                .schema(Map.of("type", "string"))
                                .build()))
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of(
                                        "type", "object",
                                        "required", List.of("prompt"),
                                        "properties", Map.of("prompt", Map.of("type", "string"))))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(
                        FieldDefinitionDto.builder()
                                .name("valA")
                                .type(SchemaFieldType.NUMBER)
                                .required(true)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("valB")
                                .type(SchemaFieldType.NUMBER)
                                .required(true)
                                .build())))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .overallScore(overallScore)
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TestCaseResponseDto createTestCaseForSuite(UUID suiteId, String name, Map<String, Object> data) {
        UUID datasetId = metaTestDataHelper.getDatasetId(suiteId);
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .data(data)
                        .build()),
                TestCaseResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TestSuiteResponseDto createTestSuiteWithComplexSchema(String name) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name)
                .description("Description for " + name)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .parameters(List.of(ParameterDefinitionDto.builder()
                                .name("q")
                                .in(ParameterLocation.QUERY)
                                .required(false)
                                .schema(Map.of("type", "string"))
                                .build()))
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(
                        FieldDefinitionDto.builder()
                                .name("expected")
                                .type(SchemaFieldType.STRING)
                                .required(true)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("tags")
                                .type(SchemaFieldType.ARRAY)
                                .required(false)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("config")
                                .type(SchemaFieldType.OBJECT)
                                .required(false)
                                .build())))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("choices[0].message.content")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
