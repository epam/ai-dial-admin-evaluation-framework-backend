package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ParameterLocation;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteDeleteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.overallscore.CustomFunction;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

@DisplayName("TestSuite Functional Tests")
public abstract class TestSuiteFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("ts-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    private DatasetResponseDto getDataset(UUID id) {
        ResponseEntity<DatasetResponseDto> r =
                restTemplate.getForEntity(apiUrl("/datasets/" + id), DatasetResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    @Test
    @DisplayName("Should create a new test suite")
    void shouldCreateTestSuite() {
        // Given
        TestSuiteRequestDto request = buildTestSuiteRequest("Test Suite 1", "Description for test suite 1");

        // When
        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isNotNull();
        assertThat(response.getBody().getName()).isEqualTo("Test Suite 1");
        assertThat(response.getBody().getDescription()).isEqualTo("Description for test suite 1");
        assertThat(response.getBody().getDeploymentRef()).isNotNull();
        assertThat(response.getBody().getDeploymentRef().getId())
                .isEqualTo(request.getDeploymentRef().getId());
        assertThat(response.getBody().getEndpointRef()).isNotNull();
        assertThat(response.getBody().getEndpointRef().getMethod())
                .isEqualTo(request.getEndpointRef().getMethod());
        DatasetResponseDto dataset = getDataset(response.getBody().getDatasetId());
        assertThat(dataset.getTestCaseSchema()).isNotNull();
        assertThat(dataset.getTestCaseSchema())
                .extracting(FieldDefinitionDto::getName)
                .containsExactly("expected");
        assertSuiteConfigValid(response.getBody());
        assertThat(response.getBody().getVersion()).isNotNull();
        assertThat(response.getBody().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should persist and return requestName and additionalRequests (request chain)")
    void shouldPersistAndReturnRequestChain() {
        // Given: a suite carrying requestName plus a one-element additionalRequests chain
        TestSuiteRequestDto request = buildTestSuiteRequest("Chained Suite", "Suite with a request chain");
        request.setRequestName("configure");
        request.setAdditionalRequests(List.of(RequestDefinitionDto.builder()
                .name("ask")
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .build()));

        // When
        ResponseEntity<TestSuiteResponseDto> createResponse =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        // Then: create response carries the chain
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().getRequestName()).isEqualTo("configure");
        assertThat(createResponse.getBody().getAdditionalRequests()).hasSize(1);
        assertThat(createResponse.getBody().getAdditionalRequests().get(0).getName())
                .isEqualTo("ask");

        // And: GET by id returns the same chain (proves persistence through the new column)
        ResponseEntity<TestSuiteResponseDto> getResponse = restTemplate.getForEntity(
                apiUrl("/test-suites/" + createResponse.getBody().getId()), TestSuiteResponseDto.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().getRequestName()).isEqualTo("configure");
        assertThat(getResponse.getBody().getAdditionalRequests()).hasSize(1);
        assertThat(getResponse.getBody().getAdditionalRequests().get(0).getRequestTemplate())
                .isNotNull();
        assertThat(getResponse
                        .getBody()
                        .getAdditionalRequests()
                        .get(0)
                        .getRequestTemplate()
                        .getUrlTemplate())
                .isEqualTo("/v1/chat");
    }

    @Test
    @DisplayName("Should default additionalRequests to empty and requestName to null when omitted")
    void shouldDefaultRequestChainWhenOmitted() {
        // Given / When: a suite created with no chain fields (legacy single-request shape)
        TestSuiteResponseDto created = createTestSuite("Legacy Single-Request Suite");

        // Then
        assertThat(created.getRequestName()).isNull();
        assertThat(created.getAdditionalRequests()).isEmpty();
    }

    @Test
    @DisplayName("Should get test suite by ID")
    void shouldGetTestSuiteById() {
        // Given
        TestSuiteResponseDto created = createTestSuite("Test Suite Get By ID");

        // When
        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.getForEntity(apiUrl("/test-suites/" + created.getId()), TestSuiteResponseDto.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo(created.getId());
        assertThat(response.getBody().getName()).isEqualTo("Test Suite Get By ID");
    }

    @Test
    @DisplayName("Should persist and return deploymentRef.type")
    void shouldPersistAndReturnDeploymentRefType() {
        // Given: a suite whose deploymentRef carries an application type
        TestSuiteRequestDto request = buildTestSuiteRequest("Deployment Type Suite", "Desc");
        request.getDeploymentRef().setType("dial-application");

        // When
        ResponseEntity<TestSuiteResponseDto> created =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        // Then: the create response echoes the type
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().getDeploymentRef().getType()).isEqualTo("dial-application");

        // And: a fresh GET returns the persisted type
        ResponseEntity<TestSuiteResponseDto> fetched = restTemplate.getForEntity(
                apiUrl("/test-suites/" + created.getBody().getId()), TestSuiteResponseDto.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().getDeploymentRef().getType()).isEqualTo("dial-application");
    }

    @Test
    @DisplayName("Should return null deploymentRef.type when omitted")
    void shouldReturnNullDeploymentRefTypeWhenOmitted() {
        // Given: buildTestSuiteRequest leaves deploymentRef.type unset
        TestSuiteResponseDto created = createTestSuite("No Deployment Type Suite");

        // When
        ResponseEntity<TestSuiteResponseDto> fetched =
                restTemplate.getForEntity(apiUrl("/test-suites/" + created.getId()), TestSuiteResponseDto.class);

        // Then: type reads back as null (optional field)
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().getDeploymentRef().getType()).isNull();
    }

    @Test
    @DisplayName("Should return 404 for non-existent test suite")
    void shouldReturn404ForNonExistentTestSuite() {
        // Given
        UUID nonExistentId = UUID.randomUUID();

        // When
        ResponseEntity<String> response =
                restTemplate.getForEntity(apiUrl("/test-suites/" + nonExistentId), String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should get all test suites with pagination")
    void shouldGetAllTestSuitesWithPagination() {
        // Given
        createTestSuite("Test Suite 1");
        createTestSuite("Test Suite 2");
        createTestSuite("Test Suite 3");

        // When
        ResponseEntity<PageResponseDto<TestSuiteResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suites?page=0&size=2&includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(2);
        assertThat(response.getBody().getTotalElements()).isEqualTo(3L);
        assertThat(response.getBody().getTotalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should sort test suites by a single key")
    void shouldSortTestSuitesSingleKey() {
        // Given
        createTestSuite("B Suite");
        createTestSuite("A Suite");
        createTestSuite("C Suite");

        // When
        ResponseEntity<PageResponseDto<TestSuiteResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suites?page=0&size=100&sort=name,asc"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent())
                .extracting(TestSuiteResponseDto::getName)
                .containsExactly("A Suite", "B Suite", "C Suite");
    }

    @Test
    @DisplayName("Should sort test suites by multiple keys in precedence order")
    void shouldSortTestSuitesMultiKey() {
        // Given: distinct names (unique constraint); primary=name asc, secondary=id desc
        createTestSuite("Suite A");
        createTestSuite("Suite B");
        createTestSuite("Suite C");

        // When
        ResponseEntity<PageResponseDto<TestSuiteResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suites?page=0&size=100&sort=name,asc&sort=id,desc"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        // Then: ordered by name asc
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent())
                .extracting(TestSuiteResponseDto::getName)
                .containsExactly("Suite A", "Suite B", "Suite C");
    }

    @Test
    @DisplayName("Should return 400 for unknown sort field")
    void shouldReturn400ForUnknownSortField() {
        // Given
        createTestSuite("Any");

        // When
        ResponseEntity<String> response =
                restTemplate.getForEntity(apiUrl("/test-suites?page=0&size=20&sort=unknownField,asc"), String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 for invalid sort direction")
    void shouldReturn400ForInvalidSortDirection() {
        // Given
        createTestSuite("Any");

        // When
        ResponseEntity<String> response =
                restTemplate.getForEntity(apiUrl("/test-suites?page=0&size=20&sort=name,up"), String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should filter test suites by name")
    void shouldFilterTestSuitesByName() {
        // Given
        createTestSuite("AlphaSuite");
        createTestSuite("BetaSuite");

        // When
        ResponseEntity<PageResponseDto<TestSuiteResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suites?page=0&size=20&filter=name:eq:AlphaSuite"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent())
                .extracting(TestSuiteResponseDto::getName)
                .containsExactly("AlphaSuite");
    }

    @Test
    @DisplayName("Should filter test suites by name with eq (case-insensitive)")
    void shouldFilterTestSuitesByNameEqCaseInsensitive() {
        // Given
        createTestSuite("AlphaSuite");
        createTestSuite("BetaSuite");

        // When — value in different case
        ResponseEntity<PageResponseDto<TestSuiteResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suites?page=0&size=20&filter=name:eq:alphasuite"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent())
                .extracting(TestSuiteResponseDto::getName)
                .containsExactly("AlphaSuite");
    }

    @Test
    @DisplayName("Should filter test suites by name with ne (case-insensitive)")
    void shouldFilterTestSuitesByNameNeCaseInsensitive() {
        // Given
        createTestSuite("AlphaSuite");
        createTestSuite("BetaSuite");

        // When — ne with different case should still exclude the matching suite
        ResponseEntity<PageResponseDto<TestSuiteResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suites?page=0&size=20&filter=name:ne:ALPHASUITE"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent())
                .extracting(TestSuiteResponseDto::getName)
                .containsExactly("BetaSuite");
    }

    @Test
    @DisplayName("Should filter test suites by name with co (substring, case-insensitive)")
    void shouldFilterTestSuitesByNameCo() {
        // Given
        createTestSuite("AlphaSuite");
        createTestSuite("BetaSuite");
        createTestSuite("GammaTool");

        // When
        ResponseEntity<PageResponseDto<TestSuiteResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suites?page=0&size=20&filter=name:co:SUITE"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent())
                .extracting(TestSuiteResponseDto::getName)
                .containsExactlyInAnyOrder("AlphaSuite", "BetaSuite");
    }

    @Test
    @DisplayName("Should return 400 when filter uses legacy contains operator")
    void shouldReturn400ForLegacyContainsOperator() {
        // When
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/test-suites?filter=name:contains:test"), HttpMethod.GET, null, String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should filter test suites by id with eq")
    void shouldFilterTestSuitesByIdEq() {
        // Given
        TestSuiteResponseDto suite1 = createTestSuite("IdFilter Suite 1");
        createTestSuite("IdFilter Suite 2");

        // When
        ResponseEntity<PageResponseDto<TestSuiteResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suites?filter=id:eq:" + suite1.getId()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent())
                .extracting(TestSuiteResponseDto::getId)
                .containsExactly(suite1.getId());
    }

    @Test
    @DisplayName("Should filter test suites by id set membership with in")
    void shouldFilterTestSuitesByIdIn() {
        // Given
        TestSuiteResponseDto suite1 = createTestSuite("IdIn Suite 1");
        TestSuiteResponseDto suite2 = createTestSuite("IdIn Suite 2");
        createTestSuite("IdIn Suite 3");

        // When
        ResponseEntity<PageResponseDto<TestSuiteResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suites?filter=id:in:" + suite1.getId() + "," + suite2.getId()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(2);
        assertThat(response.getBody().getContent())
                .extracting(TestSuiteResponseDto::getId)
                .containsExactlyInAnyOrder(suite1.getId(), suite2.getId());
    }

    @Test
    @DisplayName("Should filter test suites by description with co (case-insensitive)")
    void shouldFilterTestSuitesByDescriptionCo() {
        // Given
        TestSuiteRequestDto withEval = buildTestSuiteRequest("DescFilter Suite 1", "Evaluation pipeline config");
        TestSuiteRequestDto withoutEval = buildTestSuiteRequest("DescFilter Suite 2", "General purpose setup");
        restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(withEval), TestSuiteResponseDto.class);
        restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(withoutEval), TestSuiteResponseDto.class);

        // When — search is case-insensitive
        ResponseEntity<PageResponseDto<TestSuiteResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suites?filter=description:co:EVALUATION"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent())
                .extracting(TestSuiteResponseDto::getName)
                .containsExactly("DescFilter Suite 1");
    }

    @Test
    @DisplayName("Should filter test suites by updatedAt with ge")
    void shouldFilterTestSuitesByUpdatedAtGe() {
        // Given
        TestSuiteResponseDto created = createTestSuite("UpdatedAtFilter Suite");
        long updatedAt = created.getUpdatedAt();

        // When — ge on its own updatedAt should include it
        ResponseEntity<PageResponseDto<TestSuiteResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suites?filter=updatedAt:ge:" + updatedAt + "&filter=name:eq:UpdatedAtFilter Suite"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent())
                .extracting(TestSuiteResponseDto::getName)
                .containsExactly("UpdatedAtFilter Suite");
    }

    @Test
    @DisplayName("Should exclude test suites by updatedAt with lt")
    void shouldExcludeTestSuitesByUpdatedAtLt() {
        // Given
        TestSuiteResponseDto created = createTestSuite("UpdatedAtLt Suite");
        long updatedAt = created.getUpdatedAt();

        // When — lt on its own updatedAt should exclude it
        ResponseEntity<PageResponseDto<TestSuiteResponseDto>> response = restTemplate.exchange(
                apiUrl("/test-suites?filter=updatedAt:lt:" + updatedAt + "&filter=name:eq:UpdatedAtLt Suite"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).isEmpty();
    }

    @Test
    @DisplayName("Should keep pagination stable when sort key has ties")
    void shouldKeepPaginationStableWhenSortHasTies() {
        // Given: all suites share the same createdBy, so sorting by createdBy ties for all rows
        createTestSuite("Suite 1");
        createTestSuite("Suite 2");
        createTestSuite("Suite 3");
        createTestSuite("Suite 4");

        // When: fetch first page twice
        ResponseEntity<PageResponseDto<TestSuiteResponseDto>> first = restTemplate.exchange(
                apiUrl("/test-suites?page=0&size=2&sort=createdBy,asc"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        ResponseEntity<PageResponseDto<TestSuiteResponseDto>> second = restTemplate.exchange(
                apiUrl("/test-suites?page=0&size=2&sort=createdBy,asc"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        // Then: the same request returns the same first-page IDs
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        assertThat(first.getBody().getContent()).hasSize(2);
        assertThat(second.getBody().getContent()).hasSize(2);
        assertThat(first.getBody().getContent())
                .extracting(TestSuiteResponseDto::getId)
                .containsExactlyElementsOf(second.getBody().getContent().stream()
                        .map(TestSuiteResponseDto::getId)
                        .toList());
    }

    @Test
    @DisplayName("Should update test suite (no schema change -> 200)")
    void shouldUpdateTestSuite() {
        // Given: create suite then update name/description/deploymentRef; keep same endpoint and testCaseSchema (no
        // schema change)
        TestSuiteResponseDto created = createTestSuite("Original Name");
        TestSuiteRequestDto updateRequest = TestSuiteRequestDto.builder()
                .name("Updated Name")
                .description("Updated description")
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-updated")
                        .name("Updated Deployment")
                        .version("v2")
                        .build())
                .endpointRef(buildEndpointContract("/v1/chat"))
                .datasetId(created.getDatasetId())
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .build();

        // When (If-Match required for optimistic locking)
        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch(created.getVersion() != null ? "\"" + created.getVersion() + "\"" : "0");
        ResponseEntity<TestSuiteResponseDto> response = restTemplate.exchange(
                apiUrl("/test-suites/" + created.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers),
                TestSuiteResponseDto.class);

        // Then (no schema change -> 200 with updated suite)
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getVersion()).isNotNull();
        assertThat(response.getBody().getVersion())
                .isGreaterThan(created.getVersion() != null ? created.getVersion() : 0L);
        assertThat(response.getBody().getName()).isEqualTo("Updated Name");
        assertThat(response.getBody().getDescription()).isEqualTo("Updated description");
        assertThat(response.getBody().getDeploymentRef().getId()).isEqualTo("deployment-updated");
        assertThat(response.getBody().getEndpointRef().getRelativeUrlPattern()).isEqualTo("/v1/chat");
    }

    @Test
    @DisplayName("Should persist and return overallScore on update")
    void shouldPersistAndReturnOverallScoreOnUpdate() {
        // Given: a suite and a custom overall expression referencing one specific metric column
        TestSuiteResponseDto created = createTestSuite("Overall Score Suite");
        Map<String, Object> overallScore = Map.of(
                "entity",
                "eval_summaries",
                "mode",
                "aggregate",
                "select",
                List.of(Map.of(
                        "expr",
                        Map.of(
                                "type",
                                "fn",
                                "name",
                                "avg",
                                "args",
                                List.of(Map.of("type", "field", "name", "metric::Relevancy::score"))),
                        "as",
                        "value")));
        CustomFunction overallScoreDefinition = new CustomFunction(overallScore);
        TestSuiteRequestDto updateRequest = TestSuiteRequestDto.builder()
                .name(created.getName())
                .description(created.getDescription())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(buildEndpointContract("/v1/chat"))
                .datasetId(created.getDatasetId())
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .overallScore(overallScoreDefinition)
                .build();

        // When (If-Match required for optimistic locking)
        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch(created.getVersion() != null ? "\"" + created.getVersion() + "\"" : "0");
        ResponseEntity<TestSuiteResponseDto> response = restTemplate.exchange(
                apiUrl("/test-suites/" + created.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers),
                TestSuiteResponseDto.class);

        // Then: the response echoes the submitted overallScore
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getOverallScore()).isEqualTo(overallScoreDefinition);

        // And: it is persisted — a fresh GET returns the same overallScore
        ResponseEntity<TestSuiteResponseDto> fetched =
                restTemplate.getForEntity(apiUrl("/test-suites/" + created.getId()), TestSuiteResponseDto.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().getOverallScore()).isEqualTo(overallScoreDefinition);
    }

    @Test
    @DisplayName("Should persist and return overallScoreThreshold on create")
    void shouldPersistAndReturnOverallScoreThresholdOnCreate() {
        // Given: a create request including overallScoreThreshold
        TestSuiteRequestDto request = buildTestSuiteRequest("Threshold Suite Create", "Description");
        request.setOverallScoreThreshold(0.8);

        // When
        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getOverallScoreThreshold()).isEqualTo(0.8);
        assertThat(response.getBody().isValid()).isTrue();
        assertThat(response.getBody().getValidationWarnings()).isEmpty();
    }

    @Test
    @DisplayName("Should persist and return overallScoreThreshold on update")
    void shouldPersistAndReturnOverallScoreThresholdOnUpdate() {
        // Given: a suite and an update request carrying overallScoreThreshold
        TestSuiteResponseDto created = createTestSuite("Threshold Suite Update");
        TestSuiteRequestDto updateRequest = TestSuiteRequestDto.builder()
                .name(created.getName())
                .description(created.getDescription())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(buildEndpointContract("/v1/chat"))
                .datasetId(created.getDatasetId())
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .overallScoreThreshold(0.9)
                .build();

        // When (If-Match required for optimistic locking)
        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch(created.getVersion() != null ? "\"" + created.getVersion() + "\"" : "0");
        ResponseEntity<TestSuiteResponseDto> response = restTemplate.exchange(
                apiUrl("/test-suites/" + created.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers),
                TestSuiteResponseDto.class);

        // Then: the response echoes the submitted overallScoreThreshold
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getOverallScoreThreshold()).isEqualTo(0.9);
        assertThat(response.getBody().isValid()).isTrue();
        assertThat(response.getBody().getValidationWarnings()).isEmpty();

        // And: it is persisted — a fresh GET returns the same overallScoreThreshold
        ResponseEntity<TestSuiteResponseDto> fetched =
                restTemplate.getForEntity(apiUrl("/test-suites/" + created.getId()), TestSuiteResponseDto.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().getOverallScoreThreshold()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("Should leave overallScoreThreshold null when omitted on create")
    void shouldLeaveOverallScoreThresholdNull_whenOmittedOnCreate() {
        // Given/When: a suite created without overallScoreThreshold
        TestSuiteResponseDto created = createTestSuite("Threshold Suite Omitted");

        // Then
        assertThat(created.getOverallScoreThreshold()).isNull();
    }

    @Test
    @DisplayName("Should return 400 when overallScoreThreshold is below 0.0")
    void shouldReturn400_whenOverallScoreThresholdBelowMin() {
        // Given
        TestSuiteRequestDto request = buildTestSuiteRequest("Threshold Suite Below Min", "Description");
        request.setOverallScoreThreshold(-0.1);

        // When
        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when overallScoreThreshold is above 1.0")
    void shouldReturn400_whenOverallScoreThresholdAboveMax() {
        // Given
        TestSuiteRequestDto request = buildTestSuiteRequest("Threshold Suite Above Max", "Description");
        request.setOverallScoreThreshold(1.1);

        // When
        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should accept overallScoreThreshold at the 0.0 and 1.0 boundaries")
    void shouldAcceptOverallScoreThreshold_atBoundaries() {
        // Given/When: create with the lower boundary
        TestSuiteRequestDto lowerRequest = buildTestSuiteRequest("Threshold Suite Lower Bound", "Description");
        lowerRequest.setOverallScoreThreshold(0.0);
        ResponseEntity<TestSuiteResponseDto> lowerResponse = restTemplate.postForEntity(
                apiUrl("/test-suites"), jsonEntity(lowerRequest), TestSuiteResponseDto.class);

        // Then
        assertThat(lowerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(lowerResponse.getBody()).isNotNull();
        assertThat(lowerResponse.getBody().getOverallScoreThreshold()).isEqualTo(0.0);

        // Given/When: create with the upper boundary
        TestSuiteRequestDto upperRequest = buildTestSuiteRequest("Threshold Suite Upper Bound", "Description");
        upperRequest.setOverallScoreThreshold(1.0);
        ResponseEntity<TestSuiteResponseDto> upperResponse = restTemplate.postForEntity(
                apiUrl("/test-suites"), jsonEntity(upperRequest), TestSuiteResponseDto.class);

        // Then
        assertThat(upperResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(upperResponse.getBody()).isNotNull();
        assertThat(upperResponse.getBody().getOverallScoreThreshold()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should return 409 when If-Match version does not match")
    void shouldReturn409OnVersionConflict() {
        // Given
        TestSuiteResponseDto created = createTestSuite("Original");
        TestSuiteRequestDto updateRequest = TestSuiteRequestDto.builder()
                .name("Updated")
                .description("desc")
                .datasetId(created.getDatasetId())
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d").name("D").build())
                .endpointRef(buildEndpointContract("/path"))
                .build();

        // When: send stale version (e.g. 0 when current is 0 but we send 99)
        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch("\"99\"");
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/test-suites/" + created.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers),
                String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
    }

    @Test
    @DisplayName("Should create suites with different names successfully")
    void shouldCreateSuitesWithDifferentNamesSuccessfully() {
        TestSuiteRequestDto request1 = buildTestSuiteRequest("Suite A", "Desc A");
        TestSuiteRequestDto request2 = buildTestSuiteRequest("Suite B", "Desc B");

        ResponseEntity<TestSuiteResponseDto> r1 =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request1), TestSuiteResponseDto.class);
        ResponseEntity<TestSuiteResponseDto> r2 =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request2), TestSuiteResponseDto.class);

        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r1.getBody()).isNotNull();
        assertThat(r2.getBody()).isNotNull();
        assertThat(r1.getBody().getName()).isEqualTo("Suite A");
        assertThat(r2.getBody().getName()).isEqualTo("Suite B");
    }

    @Test
    @DisplayName("Should update suite to its own current name (no false-positive 409)")
    void shouldUpdateSuiteToOwnCurrentNameSuccessfully() {
        TestSuiteResponseDto created = createTestSuite("My Suite");
        TestSuiteRequestDto updateRequest = buildTestSuiteRequest("My Suite", "Same name, updated desc");
        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch(created.getVersion() != null ? "\"" + created.getVersion() + "\"" : "0");

        ResponseEntity<TestSuiteResponseDto> response = restTemplate.exchange(
                apiUrl("/test-suites/" + created.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers),
                TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getName()).isEqualTo("My Suite");
        assertThat(response.getBody().getDescription()).isEqualTo("Same name, updated desc");
    }

    @Test
    @DisplayName("Should update suite to case variation of own name (no false-positive 409)")
    void shouldUpdateSuiteToCaseVariationOfOwnNameSuccessfully() {
        TestSuiteResponseDto created = createTestSuite("Alpha");
        TestSuiteRequestDto updateRequest = buildTestSuiteRequest("alpha", "Case variation of same suite");
        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch(created.getVersion() != null ? "\"" + created.getVersion() + "\"" : "0");

        ResponseEntity<TestSuiteResponseDto> response = restTemplate.exchange(
                apiUrl("/test-suites/" + created.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers),
                TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getName()).isEqualTo("alpha");
    }

    @Test
    @DisplayName("Should return 409 when creating test suite with duplicate name")
    void shouldReturn409WhenCreatingTestSuiteWithDuplicateName() {
        createTestSuite("Unique Name");
        TestSuiteRequestDto request = buildTestSuiteRequest("Unique Name", "Description");

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("UNIQUE_CONSTRAINT_VIOLATION");
        assertThat(response.getBody()).contains("Unique Name");
    }

    @Test
    @DisplayName("Should return 409 when creating test suite with name that differs only by case")
    void shouldReturn409WhenCreatingTestSuiteWithCaseVariation() {
        createTestSuite("Alpha");
        TestSuiteRequestDto request = buildTestSuiteRequest("alpha", "Description");

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("UNIQUE_CONSTRAINT_VIOLATION");
    }

    @Test
    @DisplayName("Should return 409 when updating test suite to name that another suite already has")
    void shouldReturn409WhenUpdatingTestSuiteToDuplicateName() {
        createTestSuite("Name One");
        TestSuiteResponseDto toUpdate = createTestSuite("Name Two");
        TestSuiteRequestDto updateRequest = buildTestSuiteRequest("Name One", "Desc");
        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch(toUpdate.getVersion() != null ? "\"" + toUpdate.getVersion() + "\"" : "0");

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/test-suites/" + toUpdate.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(updateRequest, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("UNIQUE_CONSTRAINT_VIOLATION");
        assertThat(response.getBody()).contains("Name One");
    }

    // Schema-mutation tests (formerly PUT /test-suites/{id} with .testCaseSchema(...) → 202 + RevalidationTaskDto)
    // and the suite-scoped /revalidation-tasks endpoints have moved to /datasets/{id}. Covered by
    // DatasetCrudFunctionalTest (15.1) and RevalidationTaskFunctionalTest (15.5).

    @Test
    @DisplayName("Should delete test suite")
    void shouldDeleteTestSuite() {
        // Given
        TestSuiteResponseDto created = createTestSuite("Test Suite to Delete");

        // When
        ResponseEntity<TestSuiteDeleteResponseDto> deleteResponse = restTemplate.exchange(
                apiUrl("/test-suites/" + created.getId()),
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                TestSuiteDeleteResponseDto.class);

        // Then
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deleteResponse.getBody()).isNotNull();
        assertThat(deleteResponse.getBody().isDeleted()).isTrue();

        // Verify it's deleted
        ResponseEntity<String> getResponse =
                restTemplate.getForEntity(apiUrl("/test-suites/" + created.getId()), String.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 400 for invalid request body")
    void shouldReturn400ForInvalidRequestBody() {
        // Given - name is required
        TestSuiteRequestDto request =
                TestSuiteRequestDto.builder().description("Description only").build();

        // When
        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Field [name]: Name is required");
    }

    @Test
    @DisplayName("Should save TestSuite when JSON Schema is valid")
    void shouldSaveTestSuiteWhenJsonSchemaValid() {
        TestSuiteRequestDto request = buildTestSuiteRequest("Valid Schema Suite", "Desc");

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getName()).isEqualTo("Valid Schema Suite");
    }

    @Test
    @DisplayName("Should return 400 for invalid requestBodySchema type (e.g. abc)")
    void shouldReturn400ForInvalidRequestBodySchemaType() {
        EndpointContractDto endpoint = buildEndpointContract("/v1/chat");
        endpoint.setRequestBodySchema(JsonRequestBodySchemaDto.builder()
                .schema(Map.of("type", "abc", "properties", Map.of()))
                .build());
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Invalid Schema Type")
                .description("Desc")
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(endpoint)
                .datasetId(newDatasetWithSchema(List.of()))
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("requestBodySchema");
    }

    // Schema-field validation tests (blank name, null type, colon in name) moved to DatasetCrudFunctionalTest (15.1).

    @Test
    @DisplayName("Should return 400 when parameter missing in")
    void shouldReturn400WhenParameterMissingIn() {
        EndpointContractDto endpoint = EndpointContractDto.builder()
                .method(HttpMethod.POST)
                .relativeUrlPattern("/v1/chat")
                .parameters(List.of(ParameterDefinitionDto.builder()
                        .name("query")
                        .in(null)
                        .required(true)
                        .schema(Map.of("type", "string"))
                        .build()))
                .requestBodySchema(JsonRequestBodySchemaDto.builder()
                        .schema(Map.of("type", "object", "properties", Map.of()))
                        .build())
                .build();
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Param Missing In")
                .description("Desc")
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(endpoint)
                .datasetId(newDatasetWithSchema(List.of()))
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when schema contains $ref")
    void shouldReturn400WhenSchemaContainsRef() {
        EndpointContractDto endpoint = buildEndpointContract("/v1/chat");
        endpoint.setRequestBodySchema(JsonRequestBodySchemaDto.builder()
                .schema(Map.of(
                        "type", "object",
                        "$ref", "#/definitions/Prompt",
                        "properties", Map.of("prompt", Map.of("type", "string"))))
                .build());
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Schema With Ref")
                .description("Desc")
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(endpoint)
                .datasetId(newDatasetWithSchema(List.of()))
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("$ref");
    }

    @Test
    @DisplayName("Should return 400 when parameter has invalid in enum value")
    void shouldReturn400WhenParameterInvalidInEnum() {
        String invalidBody =
                "{\"name\":\"Invalid Param In\",\"description\":\"Desc\",\"deploymentRef\":{\"id\":\"d1\",\"name\":\"D1\"},"
                        + "\"endpointRef\":{\"method\":\"POST\",\"relativeUrlPattern\":\"/v1/chat\",\"parameters\":"
                        + "[{\"name\":\"q\",\"in\":\"cookie\",\"required\":true,\"schema\":{\"type\":\"string\"}}],"
                        + "\"requestBodySchema\":{\"type\":\"object\",\"properties\":{}},\"responseBodySchema\":null},"
                        + "\"testCaseSchema\":[]}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites"), new HttpEntity<>(invalidBody, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when filter count exceeds 32")
    void shouldReturn400WhenFilterCountExceeds32() {
        StringBuilder url = new StringBuilder(apiUrl("/test-suites?page=0&size=20"));
        for (int i = 0; i < 33; i++) {
            url.append("&filter=name:eq:x").append(i);
        }
        ResponseEntity<String> response = restTemplate.getForEntity(url.toString(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when sort count exceeds 32")
    void shouldReturn400WhenSortCountExceeds32() {
        StringBuilder url = new StringBuilder(apiUrl("/test-suites?page=0&size=20"));
        for (int i = 0; i < 33; i++) {
            url.append("&sort=name,asc");
        }
        ResponseEntity<String> response = restTemplate.getForEntity(url.toString(), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should accept filter and sort each at 32")
    void shouldAcceptFilterAndSortAt32() {
        createTestSuite("For Limit 32");
        StringBuilder url = new StringBuilder(apiUrl("/test-suites?page=0&size=20"));
        for (int i = 0; i < 32; i++) {
            url.append("&filter=name:eq:For Limit 32");
        }
        for (int i = 0; i < 32; i++) {
            url.append("&sort=name,asc");
        }
        ResponseEntity<PageResponseDto<TestSuiteResponseDto>> response =
                restTemplate.exchange(url.toString(), HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    @DisplayName("Should return 400 when responseColumns name contains a double colon")
    void shouldReturn400WhenResponseColumnNameContainsDoubleColon() {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Double Colon In Response Column Name")
                .description("Desc")
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(buildEndpointContract("/v1/chat"))
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("with::colon")
                        .expression("choices[0].message.content")
                        .build()))
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("'::'");
    }

    // PUT-with-colon-in-schema-field-name test moved to DatasetCrudFunctionalTest (15.1).

    private TestSuiteResponseDto createTestSuite(String name) {
        TestSuiteRequestDto request = buildTestSuiteRequest(name, "Description for " + name);

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TestSuiteRequestDto buildTestSuiteRequest(String name, String description) {
        return TestSuiteRequestDto.builder()
                .name(name)
                .description(description)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(buildEndpointContract("/v1/chat"))
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .build();
    }

    private EndpointContractDto buildEndpointContract(String relativeUrlPattern) {
        return EndpointContractDto.builder()
                .method(HttpMethod.POST)
                .relativeUrlPattern(relativeUrlPattern)
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
                .build();
    }
}
