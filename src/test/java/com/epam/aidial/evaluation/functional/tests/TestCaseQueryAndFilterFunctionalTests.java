package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
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

/**
 * Functional tests for querying {@code test_cases} through the structured-query DSL (schema-aware,
 * array-containment) and for the suite-level {@code testCaseFilter} write-time validation.
 */
@DisplayName("Test Case Query + Suite Filter Functional Tests")
public abstract class TestCaseQueryAndFilterFunctionalTests extends BaseFunctionalTest {

    private static final String SCHEMA_JSON = "[{\"name\":\"category\",\"type\":\"STRING\",\"required\":true},"
            + "{\"name\":\"tags\",\"type\":\"ARRAY\"}]";

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    private UUID seedDatasetWithTestCases() {
        Dataset dataset = metaTestDataHelper.createDataset("tc-query-" + UUID.randomUUID(), SCHEMA_JSON);
        UUID datasetId = dataset.getId();
        metaTestDataHelper.seedTestCaseInDataset(datasetId, "tc-a", "{\"category\":\"A\",\"tags\":[\"x\",\"text\"]}");
        metaTestDataHelper.seedTestCaseInDataset(datasetId, "tc-b", "{\"category\":\"B\",\"tags\":[\"y\"]}");
        metaTestDataHelper.seedTestCaseInDataset(datasetId, "tc-c", "{\"category\":\"A\",\"tags\":[\"text\",\"z\"]}");
        return datasetId;
    }

    /**
     * Seeds seven cases whose {@code tags} values discriminate the case-insensitive whole-element
     * semantics of {@code co} on an array field: a differently-cased element and an exactly-matching
     * element match {@code "tee"}; an element merely containing it as a substring, and an unrelated
     * element, do not. Three further rows exercise the type guard, the text-rendering comparison and
     * null polarity — {@code tc-offtype} holds a scalar in the {@code ARRAY}-declared field (a coerced
     * import can produce this, and expanding it unguarded aborts the whole statement),
     * {@code tc-numeric} holds numeric elements, and {@code tc-absent} has no {@code tags} key at all.
     */
    private UUID seedDatasetWithCaseVaryingTags() {
        Dataset dataset = metaTestDataHelper.createDataset("tc-ci-array-" + UUID.randomUUID(), SCHEMA_JSON);
        UUID datasetId = dataset.getId();
        metaTestDataHelper.seedTestCaseInDataset(
                datasetId, "tc-upper", "{\"category\":\"A\",\"tags\":[\"Tee\",\"x\"]}");
        metaTestDataHelper.seedTestCaseInDataset(datasetId, "tc-exact", "{\"category\":\"A\",\"tags\":[\"tee\"]}");
        metaTestDataHelper.seedTestCaseInDataset(
                datasetId, "tc-substring", "{\"category\":\"A\",\"tags\":[\"tee-shirt\"]}");
        metaTestDataHelper.seedTestCaseInDataset(datasetId, "tc-other", "{\"category\":\"A\",\"tags\":[\"y\"]}");
        metaTestDataHelper.seedTestCaseInDataset(datasetId, "tc-offtype", "{\"category\":\"A\",\"tags\":\"tee\"}");
        metaTestDataHelper.seedTestCaseInDataset(datasetId, "tc-numeric", "{\"category\":\"A\",\"tags\":[1,2]}");
        metaTestDataHelper.seedTestCaseInDataset(datasetId, "tc-absent", "{\"category\":\"A\"}");
        return datasetId;
    }

    /**
     * The filter shape the Admin UI stores for a "Contains" / "Not contain" run condition: the left
     * operand is the array field wrapped in the case-normalizing {@code lower} function (GH #142).
     */
    private static String lowerWrappedTagsComparison(String op, String value) {
        return "{\"op\":\"" + op + "\",\"args\":["
                + "{\"type\":\"fn\",\"name\":\"lower\",\"args\":[{\"type\":\"field\",\"name\":\"data::tags\"}]},"
                + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"" + value + "\"}]}";
    }

    private ResponseEntity<Map<String, Object>> execute(String bodyJson) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                apiUrl("/queries/execute"),
                HttpMethod.POST,
                new HttpEntity<>(bodyJson, headers),
                new ParameterizedTypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static int rowCount(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).isNotNull();
        return ((List<Object>) response.getBody().get("rows")).size();
    }

    @SuppressWarnings("unchecked")
    private static List<String> testCaseNames(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).isNotNull();
        return ((List<Map<String, Object>>) response.getBody().get("rows"))
                .stream().map(row -> String.valueOf(row.get("test_case_name"))).toList();
    }

    /** A fully-configured DEPLOYMENT suite request bound to {@code datasetId} (create succeeds regardless of filter). */
    private static TestSuiteRequestDto.TestSuiteRequestDtoBuilder fullSuiteRequest(String name, UUID datasetId) {
        return TestSuiteRequestDto.builder()
                .name(name)
                .datasetId(datasetId)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .build())
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build());
    }

    @Test
    @DisplayName("execute over test_cases returns all rows scoped to the dataset")
    void executeScopedByDataset() {
        UUID datasetId = seedDatasetWithTestCases();
        ResponseEntity<Map<String, Object>> response = execute("{\"entity\":\"test_cases\",\"mode\":\"row\","
                + "\"filter\":{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"dataset_id\"},"
                + "{\"type\":\"value\",\"value_type\":\"uuid\",\"value\":\"" + datasetId + "\"}]},"
                + "\"page\":{\"type\":\"offset\",\"offset\":0,\"limit\":50}}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rowCount(response)).isEqualTo(3);
    }

    @Test
    @DisplayName(
            "execute over test_cases with array-field CONTAINS returns only rows whose data::tags contains the element")
    void executeArrayContains() {
        UUID datasetId = seedDatasetWithTestCases();
        ResponseEntity<Map<String, Object>> response = execute("{\"entity\":\"test_cases\",\"mode\":\"row\","
                + "\"filter\":{\"op\":\"and\",\"args\":["
                + "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"dataset_id\"},"
                + "{\"type\":\"value\",\"value_type\":\"uuid\",\"value\":\"" + datasetId + "\"}]},"
                + "{\"op\":\"co\",\"args\":[{\"type\":\"field\",\"name\":\"data::tags\"},"
                + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"text\"}]}]},"
                + "\"page\":{\"type\":\"offset\",\"offset\":0,\"limit\":50}}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // tc-a and tc-c contain "text"; tc-b does not
        assertThat(rowCount(response)).isEqualTo(2);
    }

    @Test
    @DisplayName("execute with a lower()-wrapped array-field CONTAINS matches whole elements ignoring case")
    void executeCaseInsensitiveArrayContains() {
        UUID datasetId = seedDatasetWithCaseVaryingTags();
        ResponseEntity<Map<String, Object>> response = execute("{\"entity\":\"test_cases\",\"mode\":\"row\","
                + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"test_case_name\"}}],"
                + "\"filter\":{\"op\":\"and\",\"args\":["
                + "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"dataset_id\"},"
                + "{\"type\":\"value\",\"value_type\":\"uuid\",\"value\":\"" + datasetId + "\"}]},"
                + lowerWrappedTagsComparison("co", "tee") + "]},"
                + "\"page\":{\"type\":\"offset\",\"offset\":0,\"limit\":50}}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(testCaseNames(response)).containsExactlyInAnyOrder("tc-upper", "tc-exact");
    }

    @Test
    @DisplayName("execute with a lower()-wrapped array-field NOT CONTAIN matches non-array, numeric and absent rows")
    void executeCaseInsensitiveArrayNotContains() {
        UUID datasetId = seedDatasetWithCaseVaryingTags();
        ResponseEntity<Map<String, Object>> response = execute("{\"entity\":\"test_cases\",\"mode\":\"row\","
                + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"test_case_name\"}}],"
                + "\"filter\":{\"op\":\"and\",\"args\":["
                + "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"dataset_id\"},"
                + "{\"type\":\"value\",\"value_type\":\"uuid\",\"value\":\"" + datasetId + "\"}]},"
                + lowerWrappedTagsComparison("nc", "tee") + "]},"
                + "\"page\":{\"type\":\"offset\",\"offset\":0,\"limit\":50}}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // The scalar-valued and numeric-element rows do not contain the element, so they match `nc`
        // instead of aborting the statement, and a row with no `tags` at all matches too (null polarity).
        assertThat(testCaseNames(response))
                .containsExactlyInAnyOrder("tc-substring", "tc-other", "tc-offtype", "tc-numeric", "tc-absent");
    }

    @Test
    @DisplayName("execute with a lower()-wrapped array-field CONTAINS compares elements by their text rendering")
    void executeCaseInsensitiveArrayContainsNumericElementAsText() {
        UUID datasetId = seedDatasetWithCaseVaryingTags();
        ResponseEntity<Map<String, Object>> response = execute("{\"entity\":\"test_cases\",\"mode\":\"row\","
                + "\"select\":[{\"expr\":{\"type\":\"field\",\"name\":\"test_case_name\"}}],"
                + "\"filter\":{\"op\":\"and\",\"args\":["
                + "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"dataset_id\"},"
                + "{\"type\":\"value\",\"value_type\":\"uuid\",\"value\":\"" + datasetId + "\"}]},"
                + lowerWrappedTagsComparison("co", "1") + "]},"
                + "\"page\":{\"type\":\"offset\",\"offset\":0,\"limit\":50}}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(testCaseNames(response)).containsExactly("tc-numeric");
    }

    @Test
    @DisplayName("run creation with a lower()-wrapped array-field CONTAINS filter counts the matching cases")
    void createRunWithCaseInsensitiveArrayContainsFilter() {
        UUID datasetId = seedDatasetWithCaseVaryingTags();
        TestSuiteRequestDto request = fullSuiteRequest("Suite CI Array Filter " + UUID.randomUUID(), datasetId)
                .testCaseFilter(Map.of(
                        "op",
                        "co",
                        "args",
                        List.of(
                                Map.of(
                                        "type",
                                        "fn",
                                        "name",
                                        "lower",
                                        "args",
                                        List.of(Map.of("type", "field", "name", "data::tags"))),
                                Map.of("type", "value", "value_type", "string", "value", "tee"))))
                .build();
        ResponseEntity<TestSuiteResponseDto> suite =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(suite.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(suite.getBody()).isNotNull();

        ResponseEntity<TestSuiteRunResponseDto> run = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getBody().getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                TestSuiteRunResponseDto.class);

        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(run.getBody()).isNotNull();
        // tc-upper ("Tee") and tc-exact ("tee") match; tc-substring ("tee-shirt") and tc-other do not.
        assertThat(run.getBody().getNumberOfTestCases()).isEqualTo(2);
    }

    @Test
    @DisplayName("execute over test_cases without a dataset_id filter is rejected with 400")
    void executeWithoutDatasetIdRejected() {
        ResponseEntity<Map<String, Object>> response = execute("{\"entity\":\"test_cases\",\"mode\":\"row\","
                + "\"filter\":{\"op\":\"co\",\"args\":[{\"type\":\"field\",\"name\":\"data::tags\"},"
                + "{\"type\":\"value\",\"value_type\":\"string\",\"value\":\"text\"}]},"
                + "\"page\":{\"type\":\"offset\",\"offset\":0,\"limit\":50}}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("suite create with a valid testCaseFilter succeeds")
    void createSuiteWithValidFilter() {
        Dataset dataset = metaTestDataHelper.createDataset("filter-ok-" + UUID.randomUUID(), SCHEMA_JSON);
        TestSuiteRequestDto request = fullSuiteRequest("Suite With Filter " + UUID.randomUUID(), dataset.getId())
                .testCaseFilter(Map.of(
                        "op",
                        "eq",
                        "args",
                        List.of(
                                Map.of("type", "field", "name", "data::category"),
                                Map.of("type", "value", "value_type", "string", "value", "A"))))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTestCaseFilter()).isNotNull();
    }

    @Test
    @DisplayName("suite create with a filter referencing an unknown field is rejected with 400")
    void createSuiteWithUnknownFieldFilter() {
        Dataset dataset = metaTestDataHelper.createDataset("filter-bad-" + UUID.randomUUID(), SCHEMA_JSON);
        TestSuiteRequestDto request = fullSuiteRequest("Suite Bad Filter " + UUID.randomUUID(), dataset.getId())
                .testCaseFilter(Map.of(
                        "op",
                        "eq",
                        "args",
                        List.of(
                                Map.of("type", "field", "name", "data::doesNotExist"),
                                Map.of("type", "value", "value_type", "string", "value", "A"))))
                .build();

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl("/test-suites"), HttpMethod.POST, jsonEntity(request), new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("suite create with a testCaseFilter but no dataset is rejected with 400")
    void createUnboundSuiteWithFilterRejected() {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Unbound Filter Suite " + UUID.randomUUID())
                .testCaseFilter(Map.of(
                        "op",
                        "eq",
                        "args",
                        List.of(
                                Map.of("type", "field", "name", "data::category"),
                                Map.of("type", "value", "value_type", "string", "value", "A"))))
                .build();

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl("/test-suites"), HttpMethod.POST, jsonEntity(request), new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
