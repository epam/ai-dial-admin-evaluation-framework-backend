package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseBatchPutItemDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("TestCase Batch PUT Functional Tests")
public abstract class TestCaseBatchPutFunctionalTests extends BaseTestCaseBatchFunctionalTests {

    // ---- 7.1: Happy path tests ----

    @Test
    @DisplayName("Should batch update multiple test cases and return ordered response")
    void shouldBatchUpdateMultipleTestCases() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc1 = createTestCase(suite.getId(), "TC 1");
        TestCaseResponseDto tc2 = createTestCase(suite.getId(), "TC 2");

        List<TestCaseBatchPutItemDto> items = List.of(
                TestCaseBatchPutItemDto.builder()
                        .id(tc2.getId())
                        .testCaseName("TC 2 Updated")
                        .data(Map.of("key", "val2"))
                        .build(),
                TestCaseBatchPutItemDto.builder()
                        .id(tc1.getId())
                        .testCaseName("TC 1 Updated")
                        .data(Map.of("key", "val1"))
                        .build());

        ResponseEntity<List<TestCaseResponseDto>> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PUT,
                jsonEntity(items),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<TestCaseResponseDto> body = response.getBody();
        assertThat(body).hasSize(2);
        // Response order must match input order
        assertThat(body.get(0).getId()).isEqualTo(tc2.getId());
        assertThat(body.get(0).getTestCaseName()).isEqualTo("TC 2 Updated");
        assertThat(body.get(0).getData()).containsEntry("key", "val2");
        assertThat(body.get(1).getId()).isEqualTo(tc1.getId());
        assertThat(body.get(1).getTestCaseName()).isEqualTo("TC 1 Updated");
    }

    @Test
    @DisplayName("Should include validation warnings when includeWarnings=true")
    void shouldIncludeValidationWarningsWhenRequested() {
        TestSuiteResponseDto suite = createTestSuiteWithSchema();
        TestCaseResponseDto tc = createTestCaseWithData(suite.getId(), "TC", Map.of("prompt", "hi", "expected", "ok"));

        List<TestCaseBatchPutItemDto> items = List.of(TestCaseBatchPutItemDto.builder()
                .id(tc.getId())
                .testCaseName("TC Updated")
                .data(Map.of()) // missing required fields
                .build());

        ResponseEntity<List<TestCaseResponseDto>> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases?includeWarnings=true"),
                HttpMethod.PUT,
                jsonEntity(items),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        TestCaseResponseDto updated = response.getBody().get(0);
        assertThat(updated.isValid()).isFalse();
        assertThat(updated.getValidationWarnings()).isNotEmpty();
    }

    @Test
    @DisplayName("Should exclude validation warnings when includeWarnings is not set")
    void shouldExcludeValidationWarningsByDefault() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC");

        List<TestCaseBatchPutItemDto> items = List.of(TestCaseBatchPutItemDto.builder()
                .id(tc.getId())
                .testCaseName("TC Updated")
                .data(Map.of())
                .build());

        ResponseEntity<List<TestCaseResponseDto>> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PUT,
                jsonEntity(items),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get(0).getValidationWarnings()).isNull();
    }

    // ---- 7.2: Error tests ----

    @Test
    @DisplayName("Should return 400 for empty array")
    void shouldReturn400ForEmptyArray() {
        TestSuiteResponseDto suite = createTestSuite();

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PUT,
                jsonEntity(List.of()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when exceeding max items")
    void shouldReturn400WhenExceedingMaxItems() {
        TestSuiteResponseDto suite = createTestSuite();

        // Build a list of 257 items (exceeds default 256)
        List<TestCaseBatchPutItemDto> items = java.util.stream.IntStream.rangeClosed(1, 257)
                .mapToObj(i -> TestCaseBatchPutItemDto.builder()
                        .id(UUID.randomUUID())
                        .testCaseName("TC " + i)
                        .data(Map.of())
                        .build())
                .toList();

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PUT,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 for duplicate IDs in batch")
    void shouldReturn400ForDuplicateIds() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC");

        List<TestCaseBatchPutItemDto> items = List.of(
                TestCaseBatchPutItemDto.builder()
                        .id(tc.getId())
                        .testCaseName("A")
                        .data(Map.of())
                        .build(),
                TestCaseBatchPutItemDto.builder()
                        .id(tc.getId())
                        .testCaseName("B")
                        .data(Map.of())
                        .build());

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PUT,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 404 for non-existent test case ID")
    void shouldReturn404ForNonExistentTestCaseId() {
        TestSuiteResponseDto suite = createTestSuite();
        UUID fakeId = UUID.randomUUID();

        List<TestCaseBatchPutItemDto> items = List.of(TestCaseBatchPutItemDto.builder()
                .id(fakeId)
                .testCaseName("A")
                .data(Map.of())
                .build());

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PUT,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 404 for non-existent dataset")
    void shouldReturn404ForNonExistentDataset() {
        UUID fakeDatasetId = UUID.randomUUID();

        List<TestCaseBatchPutItemDto> items = List.of(TestCaseBatchPutItemDto.builder()
                .id(UUID.randomUUID())
                .testCaseName("A")
                .data(Map.of())
                .build());

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + fakeDatasetId + "/test-cases"), HttpMethod.PUT, jsonEntity(items), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- 7.3: Uniqueness tests ----

    @Test
    @DisplayName("Should return 409 for duplicate names within batch")
    void shouldReturn409ForDuplicateNamesWithinBatch() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc1 = createTestCase(suite.getId(), "TC 1");
        TestCaseResponseDto tc2 = createTestCase(suite.getId(), "TC 2");

        List<TestCaseBatchPutItemDto> items = List.of(
                TestCaseBatchPutItemDto.builder()
                        .id(tc1.getId())
                        .testCaseName("Same Name")
                        .data(Map.of())
                        .build(),
                TestCaseBatchPutItemDto.builder()
                        .id(tc2.getId())
                        .testCaseName("same name")
                        .data(Map.of())
                        .build());

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PUT,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Should return 409 when name collides with existing test case outside batch")
    void shouldReturn409ForNameCollisionWithExisting() {
        TestSuiteResponseDto suite = createTestSuite();
        createTestCase(suite.getId(), "Existing");
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC to update");

        List<TestCaseBatchPutItemDto> items = List.of(TestCaseBatchPutItemDto.builder()
                .id(tc.getId())
                .testCaseName("Existing")
                .data(Map.of())
                .build());

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PUT,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- 7.4: Atomicity test ----

    @Test
    @DisplayName("Should roll back all changes when one item fails")
    void shouldRollBackAllChangesOnFailure() {
        TestSuiteResponseDto suite = createTestSuite();
        createTestCase(suite.getId(), "Blocker");
        TestCaseResponseDto tc1 = createTestCase(suite.getId(), "TC 1");
        TestCaseResponseDto tc2 = createTestCase(suite.getId(), "TC 2");

        List<TestCaseBatchPutItemDto> items = List.of(
                TestCaseBatchPutItemDto.builder()
                        .id(tc1.getId())
                        .testCaseName("TC 1 New")
                        .data(Map.of())
                        .build(),
                TestCaseBatchPutItemDto.builder()
                        .id(tc2.getId())
                        .testCaseName("Blocker")
                        .data(Map.of())
                        .build());

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PUT,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Verify tc1 was NOT updated (rolled back)
        ResponseEntity<TestCaseResponseDto> tc1After = restTemplate.getForEntity(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases/" + tc1.getId()), TestCaseResponseDto.class);
        assertThat(tc1After.getBody().getTestCaseName()).isEqualTo("TC 1");
    }

    @Test
    @DisplayName("Should swap two test case names via batch PUT")
    void shouldSwapNamesViaBatchUpdate() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc1 = createTestCase(suite.getId(), "A");
        TestCaseResponseDto tc2 = createTestCase(suite.getId(), "B");

        List<TestCaseBatchPutItemDto> items = List.of(
                TestCaseBatchPutItemDto.builder()
                        .id(tc1.getId())
                        .testCaseName("B")
                        .data(Map.of())
                        .build(),
                TestCaseBatchPutItemDto.builder()
                        .id(tc2.getId())
                        .testCaseName("A")
                        .data(Map.of())
                        .build());

        ResponseEntity<List<TestCaseResponseDto>> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PUT,
                jsonEntity(items),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getName(suite, tc1.getId())).isEqualTo("B");
        assertThat(getName(suite, tc2.getId())).isEqualTo("A");
    }

    private String getName(TestSuiteResponseDto suite, UUID id) {
        return restTemplate
                .getForEntity(
                        apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases/" + id), TestCaseResponseDto.class)
                .getBody()
                .getTestCaseName();
    }
}
