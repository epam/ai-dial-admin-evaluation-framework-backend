package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.TestSuiteMetricDefinition;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetricDeclarationTestDataProvider;
import com.epam.aidial.evaluation.service.domain.dto.AggregatedMetricDefinitionResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.ConstantBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterLocation;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.RevalidationStatus;
import com.epam.aidial.evaluation.service.domain.dto.RevalidationTaskDto;
import com.epam.aidial.evaluation.service.domain.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseBindingSourceDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteMetricDefinitionRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteMetricDefinitionResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestSuiteMetricDefinition Controller Tests")
public abstract class TestSuiteMetricDefinitionFunctionalTests extends BaseFunctionalTest {

    private static final UUID SEED_ACCURACY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SEED_ACCURACY_VERSION_ID = UUID.fromString("770e8400-e29b-41d4-a716-446655440001");
    private static final UUID SEED_LATENCY_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String SEED_LATENCY_VERSION_ID = "770e8400-e29b-41d4-a716-446655440002";
    // Version 2 of the Accuracy declaration — has inputSchema with required "reference" and optional "actual"
    private static final UUID SCHEMA_VERSION_ID = UUID.fromString("990e8400-e29b-41d4-a716-446655440099");
    // Version with an empty output schema for testing INVALID_OUTPUT_SCHEMA validation
    private static final UUID EMPTY_OUTPUT_SCHEMA_VERSION_ID = UUID.fromString("880e8400-e29b-41d4-a716-446655440088");
    private static final String INPUT_SCHEMA_WITH_REQUIRED =
            "{\"properties\":{\"reference\":{\"type\":\"string\"},\"actual\":{\"type\":\"string\"}},\"required\":[\"reference\"]}";
    private static final String VALID_OUTPUT_SCHEMA = "{\"properties\":{\"score\":{\"type\":\"number\"}}}";

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private MetricDeclarationTestDataProvider metricDeclarationTestDataProvider;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private DialCoreDeploymentInvoker dialCoreDeploymentInvoker;

    private TestSuite testSuite;

    @BeforeEach
    void setUp() {
        metricDeclarationTestDataProvider.insertSeedMetricDeclarations();
        metricDeclarationTestDataProvider.insertSeedVersionForAccuracy();
        metricDeclarationTestDataProvider.insertVersionWithSchemas(
                SEED_LATENCY_VERSION_ID, SEED_LATENCY_ID.toString(), 1, "{}", "{}", VALID_OUTPUT_SCHEMA);
        metricDeclarationTestDataProvider.insertVersionWithSchemas(
                SCHEMA_VERSION_ID.toString(),
                SEED_ACCURACY_ID.toString(),
                2,
                "{}",
                INPUT_SCHEMA_WITH_REQUIRED,
                VALID_OUTPUT_SCHEMA);
        metricDeclarationTestDataProvider.insertVersionWithSchemas(
                EMPTY_OUTPUT_SCHEMA_VERSION_ID.toString(), SEED_ACCURACY_ID.toString(), 3, "{}", "{}", "{}");
        testSuite = metaTestDataHelper.createTestSuite("tsmd-suite-" + UUID.randomUUID());
        analyticsTestDataHelper.cleanupEvalSummaries();
    }

    private String tsmdUrl() {
        return apiUrl("/test-suites/" + testSuite.getId() + "/metric-definitions");
    }

    private String tsmdUrl(UUID id) {
        return tsmdUrl() + "/" + id;
    }

    private String tsmdAggregatedUrl(UUID id) {
        return tsmdUrl() + "/" + id + "/aggregated";
    }

    private TestSuiteMetricDefinitionRequestDto validRequest(String name) {
        return TestSuiteMetricDefinitionRequestDto.builder()
                .name(name)
                .metricDeclarationId(SEED_ACCURACY_ID)
                .metricDeclarationVersionId(SEED_ACCURACY_VERSION_ID)
                .enabled(true)
                .configBindings(List.of())
                .inputBindings(List.of())
                .build();
    }

    private TestSuiteMetricDefinitionRequestDto validRequestForLatency(String name) {
        return TestSuiteMetricDefinitionRequestDto.builder()
                .name(name)
                .metricDeclarationId(SEED_LATENCY_ID)
                .metricDeclarationVersionId(UUID.fromString(SEED_LATENCY_VERSION_ID))
                .enabled(true)
                .configBindings(List.of())
                .inputBindings(List.of())
                .build();
    }

    @Test
    @DisplayName("Should create metric definition")
    void shouldCreateMetricDefinition() {
        TestSuiteMetricDefinitionRequestDto request = validRequest("Accuracy Check");

        ResponseEntity<TestSuiteMetricDefinitionResponseDto> response =
                restTemplate.postForEntity(tsmdUrl(), jsonEntity(request), TestSuiteMetricDefinitionResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isNotNull();
        assertThat(response.getBody().getTestSuiteId()).isEqualTo(testSuite.getId());
        assertThat(response.getBody().getMetricDeclarationId()).isEqualTo(SEED_ACCURACY_ID);
        assertThat(response.getBody().getMetricDeclarationVersionId()).isEqualTo(SEED_ACCURACY_VERSION_ID);
        assertThat(response.getBody().getName()).isEqualTo("Accuracy Check");
        assertThat(response.getBody().getMetricDeclarationName()).isEqualTo("Accuracy");
        assertThat(response.getBody().isEnabled()).isTrue();
        assertThat(response.getBody().isValid()).isTrue();
        assertThat(response.getBody().getValidationWarnings()).isEmpty();
        assertThat(response.getBody().getConfigBindings()).isEmpty();
        assertThat(response.getBody().getInputBindings()).isEmpty();
        assertThat(response.getBody().getCreatedAt()).isNotNull();
        assertThat(response.getBody().getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should return 404 when test suite not found")
    void shouldReturn404_whenTestSuiteNotFound() {
        UUID nonExistentSuiteId = UUID.randomUUID();
        TestSuiteMetricDefinitionRequestDto request = validRequest("Metric");

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + nonExistentSuiteId + "/metric-definitions"),
                jsonEntity(request),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 404 when metric declaration not found")
    void shouldReturn404_whenMetricDeclarationNotFound() {
        TestSuiteMetricDefinitionRequestDto request = TestSuiteMetricDefinitionRequestDto.builder()
                .name("Unknown Metric")
                .metricDeclarationId(UUID.randomUUID())
                .metricDeclarationVersionId(UUID.randomUUID())
                .configBindings(List.of())
                .inputBindings(List.of())
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(tsmdUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 409 when duplicate name (case-insensitive)")
    void shouldReturn409_whenDuplicateName() {
        restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("MyMetric")), TestSuiteMetricDefinitionResponseDto.class);

        ResponseEntity<String> response =
                restTemplate.postForEntity(tsmdUrl(), jsonEntity(validRequest("mymetric")), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Should get metric definition by ID")
    void shouldGetMetricDefinitionById() {
        ResponseEntity<TestSuiteMetricDefinitionResponseDto> createResponse = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("Get Test")), TestSuiteMetricDefinitionResponseDto.class);
        UUID createdId = createResponse.getBody().getId();

        ResponseEntity<TestSuiteMetricDefinitionResponseDto> response =
                restTemplate.getForEntity(tsmdUrl(createdId), TestSuiteMetricDefinitionResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo(createdId);
        assertThat(response.getBody().getName()).isEqualTo("Get Test");
        assertThat(response.getBody().getMetricDeclarationName()).isEqualTo("Accuracy");
        assertThat(response.getBody().getTestSuiteId()).isEqualTo(testSuite.getId());
        assertThat(response.getBody().getMetricDeclarationId()).isEqualTo(SEED_ACCURACY_ID);
        assertThat(response.getBody().getMetricDeclarationVersionId()).isEqualTo(SEED_ACCURACY_VERSION_ID);
        assertThat(response.getBody().isEnabled()).isTrue();
        assertThat(response.getBody().isValid()).isTrue();
        assertThat(response.getBody().getValidationWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Should return 404 when metric definition not found")
    void shouldReturn404_whenMetricDefinitionNotFound() {
        ResponseEntity<String> response = restTemplate.getForEntity(tsmdUrl(UUID.randomUUID()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should list metric definitions with pagination")
    void shouldListMetricDefinitions() {
        restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("Metric A")), TestSuiteMetricDefinitionResponseDto.class);
        restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("Metric B")), TestSuiteMetricDefinitionResponseDto.class);

        ResponseEntity<PageResponseDto<TestSuiteMetricDefinitionResponseDto>> response = restTemplate.exchange(
                tsmdUrl() + "?page=0&size=10&includeTotalCount=true",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(2);
        assertThat(response.getBody().getContent())
                .allSatisfy(dto -> assertThat(dto.getMetricDeclarationName()).isEqualTo("Accuracy"));
        assertThat(response.getBody().getTotalElements()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should filter by name")
    void shouldFilterByName() {
        restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("Accuracy Score")), TestSuiteMetricDefinitionResponseDto.class);
        restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("Latency Metric")), TestSuiteMetricDefinitionResponseDto.class);

        ResponseEntity<PageResponseDto<TestSuiteMetricDefinitionResponseDto>> response = restTemplate.exchange(
                tsmdUrl() + "?page=0&size=10&filter=name:co:Accuracy&includeTotalCount=true",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        assertThat(response.getBody().getContent().get(0).getName()).isEqualTo("Accuracy Score");
    }

    @Test
    @DisplayName("Should filter by metric declaration name (eq)")
    void shouldFilterByMetricDeclarationName_eq() {
        restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("Accuracy Def")), TestSuiteMetricDefinitionResponseDto.class);
        restTemplate.postForEntity(
                tsmdUrl(),
                jsonEntity(validRequestForLatency("Latency Def")),
                TestSuiteMetricDefinitionResponseDto.class);

        ResponseEntity<PageResponseDto<TestSuiteMetricDefinitionResponseDto>> response = restTemplate.exchange(
                tsmdUrl() + "?page=0&size=10&filter=metricDeclarationName:eq:Accuracy&includeTotalCount=true",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        assertThat(response.getBody().getContent().get(0).getName()).isEqualTo("Accuracy Def");
        assertThat(response.getBody().getContent().get(0).getMetricDeclarationName())
                .isEqualTo("Accuracy");
        assertThat(response.getBody().getTotalElements()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should filter by metric declaration name (ne)")
    void shouldFilterByMetricDeclarationName_ne() {
        restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("Accuracy Def")), TestSuiteMetricDefinitionResponseDto.class);
        restTemplate.postForEntity(
                tsmdUrl(),
                jsonEntity(validRequestForLatency("Latency Def")),
                TestSuiteMetricDefinitionResponseDto.class);

        ResponseEntity<PageResponseDto<TestSuiteMetricDefinitionResponseDto>> response = restTemplate.exchange(
                tsmdUrl() + "?page=0&size=10&filter=metricDeclarationName:ne:Accuracy&includeTotalCount=true",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        assertThat(response.getBody().getContent().get(0).getName()).isEqualTo("Latency Def");
        assertThat(response.getBody().getContent().get(0).getMetricDeclarationName())
                .isEqualTo("Latency");
        assertThat(response.getBody().getTotalElements()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should filter by metric declaration name (contains)")
    void shouldFilterByMetricDeclarationName_contains() {
        restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("Accuracy Def")), TestSuiteMetricDefinitionResponseDto.class);
        restTemplate.postForEntity(
                tsmdUrl(),
                jsonEntity(validRequestForLatency("Latency Def")),
                TestSuiteMetricDefinitionResponseDto.class);

        ResponseEntity<PageResponseDto<TestSuiteMetricDefinitionResponseDto>> response = restTemplate.exchange(
                tsmdUrl() + "?page=0&size=10&filter=metricDeclarationName:co:aten&includeTotalCount=true",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        assertThat(response.getBody().getContent().get(0).getName()).isEqualTo("Latency Def");
        assertThat(response.getBody().getTotalElements()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should sort by name ascending")
    void shouldSortByName() {
        restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("Zebra Metric")), TestSuiteMetricDefinitionResponseDto.class);
        restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("Alpha Metric")), TestSuiteMetricDefinitionResponseDto.class);

        ResponseEntity<PageResponseDto<TestSuiteMetricDefinitionResponseDto>> response = restTemplate.exchange(
                tsmdUrl() + "?page=0&size=10&sort=name,asc",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent())
                .extracting(TestSuiteMetricDefinitionResponseDto::getName)
                .containsExactly("Alpha Metric", "Zebra Metric");
    }

    @Test
    @DisplayName("Should update metric definition")
    void shouldUpdateMetricDefinition() {
        ResponseEntity<TestSuiteMetricDefinitionResponseDto> createResponse = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("Original Name")), TestSuiteMetricDefinitionResponseDto.class);
        UUID createdId = createResponse.getBody().getId();

        TestSuiteMetricDefinitionRequestDto updateRequest = validRequest("Updated Name");

        ResponseEntity<TestSuiteMetricDefinitionResponseDto> response = restTemplate.exchange(
                tsmdUrl(createdId),
                HttpMethod.PUT,
                jsonEntity(updateRequest),
                TestSuiteMetricDefinitionResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getName()).isEqualTo("Updated Name");
        assertThat(response.getBody().getMetricDeclarationName()).isEqualTo("Accuracy");
        assertThat(response.getBody().isEnabled()).isTrue();
        assertThat(response.getBody().isValid()).isTrue();
        assertThat(response.getBody().getValidationWarnings()).isEmpty();
        assertThat(response.getBody().getUpdatedAt())
                .isGreaterThanOrEqualTo(createResponse.getBody().getCreatedAt());
    }

    @Test
    @DisplayName("Should return 404 when metric declaration not found on update")
    void shouldReturn404_whenMetricDeclarationNotFoundOnUpdate() {
        ResponseEntity<TestSuiteMetricDefinitionResponseDto> createResponse = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("To Update")), TestSuiteMetricDefinitionResponseDto.class);
        UUID createdId = createResponse.getBody().getId();

        TestSuiteMetricDefinitionRequestDto updateRequest = TestSuiteMetricDefinitionRequestDto.builder()
                .name("Updated")
                .metricDeclarationId(UUID.randomUUID())
                .metricDeclarationVersionId(UUID.randomUUID())
                .configBindings(List.of())
                .inputBindings(List.of())
                .build();

        ResponseEntity<String> response =
                restTemplate.exchange(tsmdUrl(createdId), HttpMethod.PUT, jsonEntity(updateRequest), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 404 when metric declaration has no versions on update")
    void shouldReturn404_whenMetricDeclarationHasNoVersionsOnUpdate() {
        ResponseEntity<TestSuiteMetricDefinitionResponseDto> createResponse = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("To Update")), TestSuiteMetricDefinitionResponseDto.class);
        UUID createdId = createResponse.getBody().getId();

        UUID noVersionDeclId = UUID.fromString("880e8400-e29b-41d4-a716-446655440099");
        metricDeclarationTestDataProvider.insertSingleDeclarationWithoutVersion(
                noVersionDeclId.toString(),
                MetricDeclarationTestDataProvider.SEED_METRIC_PROVIDER_ID,
                "NoVersionMetric");

        TestSuiteMetricDefinitionRequestDto updateRequest = TestSuiteMetricDefinitionRequestDto.builder()
                .name("Updated")
                .metricDeclarationId(noVersionDeclId)
                .metricDeclarationVersionId(UUID.randomUUID())
                .configBindings(List.of())
                .inputBindings(List.of())
                .build();

        ResponseEntity<String> response =
                restTemplate.exchange(tsmdUrl(createdId), HttpMethod.PUT, jsonEntity(updateRequest), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should delete metric definition")
    void shouldDeleteMetricDefinition() {
        ResponseEntity<TestSuiteMetricDefinitionResponseDto> createResponse = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("To Delete")), TestSuiteMetricDefinitionResponseDto.class);
        UUID createdId = createResponse.getBody().getId();

        ResponseEntity<Void> deleteResponse =
                restTemplate.exchange(tsmdUrl(createdId), HttpMethod.DELETE, null, Void.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> getResponse = restTemplate.getForEntity(tsmdUrl(createdId), String.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should cascade delete with test suite")
    void shouldCascadeDeleteWithTestSuite() {
        TestSuiteMetricDefinition tsmd = metaTestDataHelper.createTestSuiteMetricDefinition(
                testSuite.getId(), SEED_ACCURACY_ID, SEED_ACCURACY_VERSION_ID, "Cascade Test");

        restTemplate.delete(apiUrl("/test-suites/" + testSuite.getId()));

        assertThat(metaTestDataHelper.findMetricDefinition(tsmd.getId())).isEmpty();
    }

    @Test
    @DisplayName("Should return 404 when version does not belong to declaration on create")
    void shouldReturn404_whenVersionDoesNotBelongToDeclarationOnCreate() {
        TestSuiteMetricDefinitionRequestDto request = TestSuiteMetricDefinitionRequestDto.builder()
                .name("Mismatched Version")
                .metricDeclarationId(SEED_ACCURACY_ID)
                .metricDeclarationVersionId(UUID.randomUUID())
                .configBindings(List.of())
                .inputBindings(List.of())
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(tsmdUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 404 when version does not belong to declaration on update")
    void shouldReturn404_whenVersionDoesNotBelongToDeclarationOnUpdate() {
        ResponseEntity<TestSuiteMetricDefinitionResponseDto> createResponse = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("To Update Version")), TestSuiteMetricDefinitionResponseDto.class);
        UUID createdId = createResponse.getBody().getId();

        TestSuiteMetricDefinitionRequestDto updateRequest = TestSuiteMetricDefinitionRequestDto.builder()
                .name("Updated Version")
                .metricDeclarationId(SEED_ACCURACY_ID)
                .metricDeclarationVersionId(UUID.randomUUID())
                .configBindings(List.of())
                .inputBindings(List.of())
                .build();

        ResponseEntity<String> response =
                restTemplate.exchange(tsmdUrl(createdId), HttpMethod.PUT, jsonEntity(updateRequest), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 400 when metricDeclarationVersionId is missing on create")
    void shouldReturn400_whenVersionIdMissingOnCreate() {
        TestSuiteMetricDefinitionRequestDto request = TestSuiteMetricDefinitionRequestDto.builder()
                .name("Missing Version")
                .metricDeclarationId(SEED_ACCURACY_ID)
                .configBindings(List.of())
                .inputBindings(List.of())
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(tsmdUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when metricDeclarationVersionId is missing on update")
    void shouldReturn400_whenVersionIdMissingOnUpdate() {
        ResponseEntity<TestSuiteMetricDefinitionResponseDto> createResponse = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("To Update Missing")), TestSuiteMetricDefinitionResponseDto.class);
        UUID createdId = createResponse.getBody().getId();

        TestSuiteMetricDefinitionRequestDto updateRequest = TestSuiteMetricDefinitionRequestDto.builder()
                .name("Updated Missing")
                .metricDeclarationId(SEED_ACCURACY_ID)
                .configBindings(List.of())
                .inputBindings(List.of())
                .build();

        ResponseEntity<String> response =
                restTemplate.exchange(tsmdUrl(createdId), HttpMethod.PUT, jsonEntity(updateRequest), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when TSMD name contains a double colon on create")
    void shouldReturn400_whenNameContainsDoubleColonOnCreate() {
        TestSuiteMetricDefinitionRequestDto request = validRequest("Acc::uracy");

        ResponseEntity<String> response = restTemplate.postForEntity(tsmdUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("'::'");
    }

    @Test
    @DisplayName("Should return 400 when TSMD name contains a double colon on update")
    void shouldReturn400_whenNameContainsDoubleColonOnUpdate() {
        ResponseEntity<TestSuiteMetricDefinitionResponseDto> createResponse = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("To Rename")), TestSuiteMetricDefinitionResponseDto.class);
        UUID createdId = createResponse.getBody().getId();

        TestSuiteMetricDefinitionRequestDto updateRequest = validRequest("Acc::uracy");

        ResponseEntity<String> response =
                restTemplate.exchange(tsmdUrl(createdId), HttpMethod.PUT, jsonEntity(updateRequest), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("'::'");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Should persist and return all binding source types")
    void shouldPersistAndReturnBindings() {
        String requestJson = """
                {
                    "name": "Binding Round-Trip",
                    "metricDeclarationId": "%s",
                    "metricDeclarationVersionId": "%s",
                    "configBindings": [
                        {
                            "property": "threshold",
                            "source": {"$type": "Constant", "value": 0.8}
                        }
                    ],
                    "inputBindings": [
                        {
                            "property": "reference",
                            "source": {"$type": "TestCase", "columnName": "expected_output"}
                        },
                        {
                            "property": "actual",
                            "source": {"$type": "Response", "columnName": "model_answer"}
                        },
                        {
                            "property": "options",
                            "source": {"$type": "Constant", "value": {"key": "val"}}
                        }
                    ]
                }
                """.formatted(SEED_ACCURACY_ID, SEED_ACCURACY_VERSION_ID);

        ResponseEntity<TestSuiteMetricDefinitionResponseDto> response = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(requestJson), TestSuiteMetricDefinitionResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();

        assertThat(response.getBody().getConfigBindings()).hasSize(1);
        assertThat(response.getBody().getConfigBindings().get(0).getProperty()).isEqualTo("threshold");
        assertThat(response.getBody().getConfigBindings().get(0).getSource())
                .isInstanceOf(ConstantBindingSourceDto.class);

        assertThat(response.getBody().getInputBindings()).hasSize(3);
        assertThat(response.getBody().getInputBindings().get(0).getProperty()).isEqualTo("reference");
        assertThat(response.getBody().getInputBindings().get(0).getSource())
                .isInstanceOf(TestCaseBindingSourceDto.class);
        assertThat(response.getBody().getInputBindings().get(1).getProperty()).isEqualTo("actual");
        assertThat(response.getBody().getInputBindings().get(1).getSource())
                .isInstanceOf(ResponseBindingSourceDto.class);
        assertThat(response.getBody().getInputBindings().get(2).getProperty()).isEqualTo("options");
        assertThat(response.getBody().getInputBindings().get(2).getSource())
                .isInstanceOf(ConstantBindingSourceDto.class);

        // Verify via GET to confirm persistence round-trip
        UUID createdId = response.getBody().getId();
        ResponseEntity<TestSuiteMetricDefinitionResponseDto> getResponse =
                restTemplate.getForEntity(tsmdUrl(createdId), TestSuiteMetricDefinitionResponseDto.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody().getConfigBindings()).hasSize(1);
        assertThat(getResponse.getBody().getInputBindings()).hasSize(3);
    }

    @Test
    @DisplayName("Should get aggregated metric definition with declaration and version details")
    void shouldGetAggregatedMetricDefinition() {
        ResponseEntity<TestSuiteMetricDefinitionResponseDto> createResponse = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("Aggregated Test")), TestSuiteMetricDefinitionResponseDto.class);
        UUID createdId = createResponse.getBody().getId();

        ResponseEntity<AggregatedMetricDefinitionResponseDto> response =
                restTemplate.getForEntity(tsmdAggregatedUrl(createdId), AggregatedMetricDefinitionResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        // Top-level metric definition fields
        assertThat(response.getBody().getId()).isEqualTo(createdId);
        assertThat(response.getBody().getTestSuiteId()).isEqualTo(testSuite.getId());
        assertThat(response.getBody().getName()).isEqualTo("Aggregated Test");
        assertThat(response.getBody().getMetricDeclarationId()).isEqualTo(SEED_ACCURACY_ID);
        assertThat(response.getBody().getMetricDeclarationVersionId()).isEqualTo(SEED_ACCURACY_VERSION_ID);
        assertThat(response.getBody().getMetricDeclarationName()).isEqualTo("Accuracy");
        assertThat(response.getBody().getConfigBindings()).isEmpty();
        assertThat(response.getBody().getInputBindings()).isEmpty();
        assertThat(response.getBody().getCreatedAt()).isNotNull();
        assertThat(response.getBody().getUpdatedAt()).isNotNull();

        // Nested metric declaration
        assertThat(response.getBody().getMetricDeclaration()).isNotNull();
        assertThat(response.getBody().getMetricDeclaration().getId()).isEqualTo(SEED_ACCURACY_ID);
        assertThat(response.getBody().getMetricDeclaration().getProviderId())
                .isEqualTo(MetricDeclarationTestDataProvider.SEED_METRIC_PROVIDER_ID);
        assertThat(response.getBody().getMetricDeclaration().getName()).isEqualTo("Accuracy");
        assertThat(response.getBody().getMetricDeclaration().getDescription())
                .isEqualTo("Measures correctness of responses");
        assertThat(response.getBody().getMetricDeclaration().getCreatedAt()).isNotNull();

        // Nested metric declaration version
        assertThat(response.getBody().getMetricDeclarationVersion()).isNotNull();
        assertThat(response.getBody().getMetricDeclarationVersion().getId()).isEqualTo(SEED_ACCURACY_VERSION_ID);
        assertThat(response.getBody().getMetricDeclarationVersion().getMetricDeclarationId())
                .isEqualTo(SEED_ACCURACY_ID);
        assertThat(response.getBody().getMetricDeclarationVersion().getSchemaVersion())
                .isEqualTo(1);
        assertThat(response.getBody().getMetricDeclarationVersion().getConfigSchema())
                .isNotNull();
        assertThat(response.getBody().getMetricDeclarationVersion().getInputSchema())
                .isNotNull();
        assertThat(response.getBody().getMetricDeclarationVersion().getOutputSchema())
                .isNotNull();
        assertThat(response.getBody().getMetricDeclarationVersion().getDescription())
                .isEqualTo("Measures correctness of responses");
        assertThat(response.getBody().getMetricDeclarationVersion().getCreatedAt())
                .isNotNull();
    }

    @Test
    @DisplayName("Should return 404 when aggregated metric definition not found")
    void shouldReturn404_whenAggregatedMetricDefinitionNotFound() {
        ResponseEntity<String> response = restTemplate.getForEntity(tsmdAggregatedUrl(UUID.randomUUID()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 404 when aggregated metric definition belongs to different suite")
    void shouldReturn404_whenAggregatedMetricDefinitionInDifferentSuite() {
        ResponseEntity<TestSuiteMetricDefinitionResponseDto> createResponse = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("Wrong Suite Test")), TestSuiteMetricDefinitionResponseDto.class);
        UUID createdId = createResponse.getBody().getId();

        TestSuite otherSuite = metaTestDataHelper.createTestSuite("other-suite-" + UUID.randomUUID());
        String otherSuiteAggregatedUrl =
                apiUrl("/test-suites/" + otherSuite.getId() + "/metric-definitions/" + createdId + "/aggregated");

        ResponseEntity<String> response = restTemplate.getForEntity(otherSuiteAggregatedUrl, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName(
            "Should mark TSMD invalid with UNRESOLVED_REFERENCE when TestCase binding column not in suite testCaseSchema")
    void shouldMarkInvalid_whenTestCaseBindingReferencesNonExistentColumn() {
        // testSuite has testCaseSchema=[] — "non_existent" column will be unresolved
        // SCHEMA_VERSION_ID has inputSchema with required "reference" and optional "actual"
        String requestJson = """
                {
                    "name": "Unresolved TestCase Binding",
                    "metricDeclarationId": "%s",
                    "metricDeclarationVersionId": "%s",
                    "configBindings": [],
                    "inputBindings": [
                        {"property": "reference", "source": {"$type": "TestCase", "columnName": "non_existent"}}
                    ]
                }
                """.formatted(SEED_ACCURACY_ID, SCHEMA_VERSION_ID);

        ResponseEntity<TestSuiteMetricDefinitionResponseDto> response = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(requestJson), TestSuiteMetricDefinitionResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteMetricDefinitionResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isValid()).isFalse();
        assertThat(body.getValidationWarnings()).hasSize(1);
        assertThat(body.getValidationWarnings().get(0).getCode()).isEqualTo(ValidationWarningCode.UNRESOLVED_REFERENCE);
    }

    @Test
    @DisplayName(
            "Should mark TSMD invalid with UNRESOLVED_REFERENCE when Response binding column not in suite responseColumns")
    void shouldMarkInvalid_whenResponseBindingReferencesNonExistentColumn() {
        // testSuite has responseColumns=[] — "non_existent" column will be unresolved
        // SCHEMA_VERSION_ID has inputSchema with required "reference" and optional "actual"
        String requestJson = """
                {
                    "name": "Unresolved Response Binding",
                    "metricDeclarationId": "%s",
                    "metricDeclarationVersionId": "%s",
                    "configBindings": [],
                    "inputBindings": [
                        {"property": "reference", "source": {"$type": "Response", "columnName": "non_existent"}}
                    ]
                }
                """.formatted(SEED_ACCURACY_ID, SCHEMA_VERSION_ID);

        ResponseEntity<TestSuiteMetricDefinitionResponseDto> response = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(requestJson), TestSuiteMetricDefinitionResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteMetricDefinitionResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isValid()).isFalse();
        assertThat(body.getValidationWarnings()).hasSize(1);
        assertThat(body.getValidationWarnings().get(0).getCode()).isEqualTo(ValidationWarningCode.UNRESOLVED_REFERENCE);
    }

    @Test
    @DisplayName("Should mark TSMD invalid with REQUIRED warning when required property bound to null constant")
    void shouldMarkInvalid_whenRequiredPropertyBoundToNullConstant() {
        // SCHEMA_VERSION_ID has "reference" as a required property
        String requestJson = """
                {
                    "name": "Null Required Binding",
                    "metricDeclarationId": "%s",
                    "metricDeclarationVersionId": "%s",
                    "configBindings": [],
                    "inputBindings": [
                        {"property": "reference", "source": {"$type": "Constant", "value": null}}
                    ]
                }
                """.formatted(SEED_ACCURACY_ID, SCHEMA_VERSION_ID);

        ResponseEntity<TestSuiteMetricDefinitionResponseDto> response = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(requestJson), TestSuiteMetricDefinitionResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteMetricDefinitionResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isValid()).isFalse();
        assertThat(body.getValidationWarnings()).hasSize(1);
        assertThat(body.getValidationWarnings().get(0).getCode()).isEqualTo(ValidationWarningCode.REQUIRED);
    }

    @Test
    @DisplayName("Should return 400 VALIDATION_ERROR when configBindings contain duplicate property")
    void shouldReturn400_whenDuplicatePropertyInConfigBindings() {
        String requestJson = """
                {
                    "name": "Duplicate Config Binding",
                    "metricDeclarationId": "%s",
                    "metricDeclarationVersionId": "%s",
                    "configBindings": [
                        {"property": "threshold", "source": {"$type": "Constant", "value": 0.8}},
                        {"property": "threshold", "source": {"$type": "Constant", "value": 0.9}}
                    ],
                    "inputBindings": []
                }
                """.formatted(SEED_ACCURACY_ID, SEED_ACCURACY_VERSION_ID);

        ResponseEntity<String> response = restTemplate.postForEntity(tsmdUrl(), jsonEntity(requestJson), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("Should return 400 VALIDATION_ERROR when inputBindings contain duplicate property")
    void shouldReturn400_whenDuplicatePropertyInInputBindings() {
        String requestJson = """
                {
                    "name": "Duplicate Input Binding",
                    "metricDeclarationId": "%s",
                    "metricDeclarationVersionId": "%s",
                    "configBindings": [],
                    "inputBindings": [
                        {"property": "reference", "source": {"$type": "Constant", "value": "a"}},
                        {"property": "reference", "source": {"$type": "Constant", "value": "b"}}
                    ]
                }
                """.formatted(SEED_ACCURACY_ID, SEED_ACCURACY_VERSION_ID);

        ResponseEntity<String> response = restTemplate.postForEntity(tsmdUrl(), jsonEntity(requestJson), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("Should create TSMD with enabled=false and preserve enabled flag through revalidation")
    void shouldPreserveEnabledFalse_afterRevalidation() {
        TestSuiteMetricDefinitionRequestDto request = TestSuiteMetricDefinitionRequestDto.builder()
                .name("Disabled Metric")
                .metricDeclarationId(SEED_ACCURACY_ID)
                .metricDeclarationVersionId(SEED_ACCURACY_VERSION_ID)
                .enabled(false)
                .configBindings(List.of())
                .inputBindings(List.of())
                .build();

        ResponseEntity<TestSuiteMetricDefinitionResponseDto> createResponse =
                restTemplate.postForEntity(tsmdUrl(), jsonEntity(request), TestSuiteMetricDefinitionResponseDto.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody().isEnabled()).isFalse();
        assertThat(createResponse.getBody().isValid()).isTrue();
        UUID tsmdId = createResponse.getBody().getId();

        // Simulate revalidation: updateValidation only updates valid/warnings, never enabled
        metaTestDataHelper.forceTsmdInvalid(tsmdId, "[]");

        ResponseEntity<TestSuiteMetricDefinitionResponseDto> afterRevalidation =
                restTemplate.getForEntity(tsmdUrl(tsmdId), TestSuiteMetricDefinitionResponseDto.class);
        assertThat(afterRevalidation.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(afterRevalidation.getBody().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Should correct stale TSMD validation state synchronously when suite responseColumns are updated")
    void shouldCorrectStaleValidation_whenSuiteResponseColumnsUpdated() {
        // Create suite via API (with version for If-Match) — testCaseSchema=[expected], responseColumns=[]
        TestSuiteResponseDto suite = createSuiteViaApi(
                "Stale Validation Suite " + UUID.randomUUID(),
                List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build()),
                List.of());

        // Insert TSMD directly, bypassing validation — binding to "non_existent" not in testCaseSchema=[]
        String inputBindings = "[{\"property\":\"reference\",\"source\":{\"$type\":\"TestCase\","
                + "\"columnName\":\"non_existent\"}}]";
        TestSuiteMetricDefinition tsmd = metaTestDataHelper.createTestSuiteMetricDefinition(
                suite.getId(), SEED_ACCURACY_ID, SCHEMA_VERSION_ID, "Stale Metric", "[]", inputBindings);

        // Confirm stale state: inserted with valid=true but binding is broken
        assertThat(metaTestDataHelper.findMetricDefinition(tsmd.getId()))
                .hasValueSatisfying(t -> assertThat(t.isValid()).isTrue());

        // PUT suite adding a responseColumn — triggers isTsmdSchemaChanged → synchronous TSMD revalidation
        ResponseEntity<String> updateResponse = updateSuiteViaApi(
                suite,
                List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build()),
                List.of(ResponseColumnDefinitionDto.builder()
                        .name("col1")
                        .expression("$.col1")
                        .type(SchemaFieldType.STRING)
                        .build()));
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // GET TSMD — should now be invalid (non_existent not in testCaseSchema=[expected])
        ResponseEntity<TestSuiteMetricDefinitionResponseDto> tsmdResponse = restTemplate.getForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/metric-definitions/" + tsmd.getId()),
                TestSuiteMetricDefinitionResponseDto.class);
        assertThat(tsmdResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tsmdResponse.getBody().isValid()).isFalse();
        assertThat(tsmdResponse.getBody().getValidationWarnings()).hasSize(1);
        assertThat(tsmdResponse.getBody().getValidationWarnings().get(0).getCode())
                .isEqualTo(ValidationWarningCode.UNRESOLVED_REFERENCE);
    }

    @Test
    @DisplayName("Should mark valid=true when optional property bound to null constant")
    void shouldMarkValid_whenOptionalPropertyBoundToNullConstant() {
        // SCHEMA_VERSION_ID: "reference" is required, "actual" is optional
        // Binding "actual" (optional) to null constant → no REQUIRED warning
        // Binding "reference" (required) to a non-null constant → satisfies required check
        String requestJson = """
                {
                    "name": "Null Optional Binding",
                    "metricDeclarationId": "%s",
                    "metricDeclarationVersionId": "%s",
                    "configBindings": [],
                    "inputBindings": [
                        {"property": "reference", "source": {"$type": "Constant", "value": "valid-value"}},
                        {"property": "actual", "source": {"$type": "Constant", "value": null}}
                    ]
                }
                """.formatted(SEED_ACCURACY_ID, SCHEMA_VERSION_ID);

        ResponseEntity<TestSuiteMetricDefinitionResponseDto> response = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(requestJson), TestSuiteMetricDefinitionResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().isValid()).isTrue();
        assertThat(response.getBody().getValidationWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Should mark TSMD invalid with INVALID_OUTPUT_SCHEMA when metric version has empty output schema")
    void shouldMarkInvalid_whenOutputSchemaHasNoProperties() {
        String requestJson = """
                {
                    "name": "Empty Output Schema Metric",
                    "metricDeclarationId": "%s",
                    "metricDeclarationVersionId": "%s",
                    "configBindings": [],
                    "inputBindings": []
                }
                """.formatted(SEED_ACCURACY_ID, EMPTY_OUTPUT_SCHEMA_VERSION_ID);

        ResponseEntity<TestSuiteMetricDefinitionResponseDto> response = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(requestJson), TestSuiteMetricDefinitionResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteMetricDefinitionResponseDto body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isValid()).isFalse();
        assertThat(body.getValidationWarnings()).hasSize(1);
        assertThat(body.getValidationWarnings().get(0).getCode())
                .isEqualTo(ValidationWarningCode.INVALID_OUTPUT_SCHEMA);
    }

    @Test
    @DisplayName("Should produce no EvalSummary records when all TSMDs are disabled or invalid")
    void shouldProduceNoEvalSummaries_whenAllTsmdsDisabledOrInvalid() {
        // Create suite via API with testCaseSchema=[expected]
        TestSuiteResponseDto suite = createSuiteViaApi(
                "All Disabled Suite " + UUID.randomUUID(),
                List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build()),
                List.of());

        // Add 1 test case
        restTemplate.postForEntity(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName("tc-1")
                        .data(Map.of("expected", "hello"))
                        .build()),
                String.class);

        // Create TSMD with enabled=false — will be excluded from metric evaluation
        restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/metric-definitions"),
                jsonEntity(TestSuiteMetricDefinitionRequestDto.builder()
                        .name("Disabled Metric For Run")
                        .metricDeclarationId(SEED_ACCURACY_ID)
                        .metricDeclarationVersionId(SEED_ACCURACY_VERSION_ID)
                        .enabled(false)
                        .configBindings(List.of())
                        .inputBindings(List.of())
                        .build()),
                String.class);

        // Mock deployment invoker to return a successful response
        when(dialCoreDeploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
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

        // Start run and await terminal
        ResponseEntity<TestSuiteRunResponseDto> runResponse = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                TestSuiteRunResponseDto.class);
        assertThat(runResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        TestSuiteRunResponseDto completedRun =
                awaitRunTerminal(runResponse.getBody().getId(), 15);
        assertThat(completedRun.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // Metric evaluation phase was skipped — no EvalSummary records produced
        assertThat(analyticsTestDataHelper.countEvalSummaries()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Should auto-revalidate TSMDs synchronously when suite update removes a referenced responseColumn")
    void shouldAutoRevalidateTsmds_whenSuiteRemovesResponseColumn() {
        // Create suite with responseColumns=[model_answer]
        TestSuiteResponseDto suite = createSuiteViaApi(
                "Suite Remove Response Col " + UUID.randomUUID(),
                List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build()),
                List.of(ResponseColumnDefinitionDto.builder()
                        .name("model_answer")
                        .expression("choices[0].message.content")
                        .type(SchemaFieldType.STRING)
                        .build()));

        // Create TSMD via API with Response binding for "model_answer" — valid since column exists
        String tsmdRequest = """
                {
                    "name": "Response Metric",
                    "metricDeclarationId": "%s",
                    "metricDeclarationVersionId": "%s",
                    "configBindings": [],
                    "inputBindings": [
                        {"property": "reference", "source": {"$type": "Response", "columnName": "model_answer"}}
                    ]
                }
                """.formatted(SEED_ACCURACY_ID, SCHEMA_VERSION_ID);
        ResponseEntity<TestSuiteMetricDefinitionResponseDto> tsmdCreate = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/metric-definitions"),
                jsonEntity(tsmdRequest),
                TestSuiteMetricDefinitionResponseDto.class);
        assertThat(tsmdCreate.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(tsmdCreate.getBody().isValid()).isTrue();
        UUID tsmdId = tsmdCreate.getBody().getId();

        // PUT suite removing responseColumns → triggers synchronous TSMD revalidation, returns 200
        ResponseEntity<String> updateResponse = updateSuiteViaApi(
                suite,
                List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build()),
                List.of());
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // GET TSMD immediately after PUT — synchronous revalidation already completed
        ResponseEntity<TestSuiteMetricDefinitionResponseDto> tsmdResponse = restTemplate.getForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/metric-definitions/" + tsmdId),
                TestSuiteMetricDefinitionResponseDto.class);
        assertThat(tsmdResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tsmdResponse.getBody().isValid()).isFalse();
        assertThat(tsmdResponse.getBody().getValidationWarnings()).hasSize(1);
        assertThat(tsmdResponse.getBody().getValidationWarnings().get(0).getCode())
                .isEqualTo(ValidationWarningCode.UNRESOLVED_REFERENCE);
    }

    // Note: testCaseSchema-driven TSMD revalidation (sync via suite PUT and async via revalidation task)
    // moved to DatasetService after task group 4 of introduce-dataset-entity. Suite PUT no longer
    // observes schema changes, so the original premise of these two tests is gone.

    @Test
    @DisplayName("Should create metric definition with a condition and round-trip it")
    void shouldCreateMetricDefinitionWithCondition() {
        TestSuiteMetricDefinitionRequestDto request = validRequest("Conditional Metric");
        request.setCondition("$exists(response.answer)");

        ResponseEntity<TestSuiteMetricDefinitionResponseDto> response =
                restTemplate.postForEntity(tsmdUrl(), jsonEntity(request), TestSuiteMetricDefinitionResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getCondition()).isEqualTo("$exists(response.answer)");

        ResponseEntity<TestSuiteMetricDefinitionResponseDto> get = restTemplate.getForEntity(
                tsmdUrl(response.getBody().getId()), TestSuiteMetricDefinitionResponseDto.class);
        assertThat(get.getBody().getCondition()).isEqualTo("$exists(response.answer)");
    }

    @Test
    @DisplayName("Should return 400 when condition is invalid JSONata on create")
    void shouldReturn400_whenConditionInvalidJsonata() {
        TestSuiteMetricDefinitionRequestDto request = validRequest("Bad JSONata Condition");
        request.setCondition("$exists(");

        ResponseEntity<String> response = restTemplate.postForEntity(tsmdUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when condition is an unregistered custom function on create")
    void shouldReturn400_whenConditionUnknownFunction() {
        TestSuiteMetricDefinitionRequestDto request = validRequest("Unknown Fn Condition");
        request.setCondition("isLastTurn()");

        ResponseEntity<String> response = restTemplate.postForEntity(tsmdUrl(), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when condition is invalid JSONata on update")
    void shouldReturn400_whenConditionInvalidJsonataOnUpdate() {
        ResponseEntity<TestSuiteMetricDefinitionResponseDto> createResponse = restTemplate.postForEntity(
                tsmdUrl(), jsonEntity(validRequest("To Update")), TestSuiteMetricDefinitionResponseDto.class);
        UUID createdId = createResponse.getBody().getId();

        TestSuiteMetricDefinitionRequestDto updateRequest = validRequest("To Update");
        updateRequest.setCondition("$exists(");

        ResponseEntity<String> response =
                restTemplate.exchange(tsmdUrl(createdId), HttpMethod.PUT, jsonEntity(updateRequest), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should omit a metric whose condition is false and keep the eval summary SUCCESS")
    void shouldOmitMetric_whenConditionFalse_andKeepSuccess() {
        TestSuiteResponseDto suite = createSuiteViaApi(
                "Condition Skip Suite " + UUID.randomUUID(),
                List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build()),
                List.of());

        restTemplate.postForEntity(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName("tc-1")
                        .data(Map.of("expected", "hello"))
                        .build()),
                String.class);

        // Enabled + valid metric whose condition references an absent response column → false → skipped.
        TestSuiteMetricDefinitionRequestDto tsmd = TestSuiteMetricDefinitionRequestDto.builder()
                .name("Latency With Condition")
                .metricDeclarationId(SEED_LATENCY_ID)
                .metricDeclarationVersionId(UUID.fromString(SEED_LATENCY_VERSION_ID))
                .enabled(true)
                .configBindings(List.of())
                .inputBindings(List.of())
                .build();
        tsmd.setCondition("$exists(response.model_answer)");
        ResponseEntity<TestSuiteMetricDefinitionResponseDto> tsmdResponse = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/metric-definitions"),
                jsonEntity(tsmd),
                TestSuiteMetricDefinitionResponseDto.class);
        assertThat(tsmdResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(tsmdResponse.getBody().isValid()).isTrue();

        when(dialCoreDeploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
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

        ResponseEntity<TestSuiteRunResponseDto> runResponse = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                TestSuiteRunResponseDto.class);
        assertThat(runResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        TestSuiteRunResponseDto completedRun =
                awaitRunTerminal(runResponse.getBody().getId(), 15);
        assertThat(completedRun.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // The metric-evaluation phase ran (one enabled+valid TSMD), producing one eval summary; the
        // condition evaluated false, so the metric is omitted from metricValues and the summary is SUCCESS.
        List<Map<String, Object>> summaries = analyticsTestDataHelper.findEvalSummariesByRunId(
                runResponse.getBody().getId());
        assertThat(summaries).hasSize(1);
        assertThat(String.valueOf(summaries.get(0).get("execution_status"))).isEqualTo("SUCCESS");
        assertThat(String.valueOf(summaries.get(0).get("metric_values"))).doesNotContain("Latency With Condition");
    }

    // --- Helper Methods ---

    private TestSuiteResponseDto createSuiteViaApi(
            String name, List<FieldDefinitionDto> testCaseSchema, List<ResponseColumnDefinitionDto> responseColumns) {
        TestSuiteRequestDto request = buildSuiteRequest(name, testCaseSchema, responseColumns);
        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<String> updateSuiteViaApi(
            TestSuiteResponseDto suite,
            List<FieldDefinitionDto> testCaseSchema,
            List<ResponseColumnDefinitionDto> responseColumns) {
        TestSuiteRequestDto request = buildSuiteRequest(suite.getName(), testCaseSchema, responseColumns);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setIfMatch(suite.getVersion() != null ? "\"" + suite.getVersion() + "\"" : "\"0\"");
        return restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(request, headers),
                String.class);
    }

    private TestSuiteRequestDto buildSuiteRequest(
            String name, List<FieldDefinitionDto> testCaseSchema, List<ResponseColumnDefinitionDto> responseColumns) {
        String schemaJson;
        try {
            schemaJson = new ObjectMapper().writeValueAsString(testCaseSchema);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
        com.epam.aidial.evaluation.data.db.model.Dataset dataset =
                metaTestDataHelper.createDataset("tsmd-ds-" + UUID.randomUUID(), schemaJson);
        return TestSuiteRequestDto.builder()
                .name(name)
                .datasetId(dataset.getId())
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
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .responseColumns(responseColumns)
                .build();
    }

    private RevalidationTaskDto awaitRevalidationCompleted(UUID testSuiteId, UUID taskId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<RevalidationTaskDto> get = restTemplate.getForEntity(
                    apiUrl("/test-suites/" + testSuiteId + "/revalidation-tasks/" + taskId), RevalidationTaskDto.class);
            if (get.getStatusCode() == HttpStatus.OK && get.getBody() != null) {
                RevalidationStatus status = get.getBody().getStatus();
                if (status == RevalidationStatus.COMPLETED
                        || status == RevalidationStatus.FAILED
                        || status == RevalidationStatus.TIMED_OUT) {
                    return get.getBody();
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while polling revalidation task", e);
            }
        }
        throw new AssertionError("Revalidation task did not complete within " + timeoutSeconds + " seconds");
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
}
