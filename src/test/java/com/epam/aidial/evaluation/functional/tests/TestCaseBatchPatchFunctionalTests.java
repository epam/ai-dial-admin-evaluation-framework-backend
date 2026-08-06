package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("TestCase Batch PATCH Functional Tests")
public abstract class TestCaseBatchPatchFunctionalTests extends BaseTestCaseBatchFunctionalTests {

    // ---- 8.1: Happy path tests ----

    @Test
    @DisplayName("Should batch patch multiple test cases with merge-patch semantics")
    void shouldBatchPatchMultipleTestCases() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc1 = createTestCaseWithData(suite.getId(), "TC 1", Map.of("a", "1", "b", "2"));
        TestCaseResponseDto tc2 = createTestCase(suite.getId(), "TC 2");

        List<Map<String, Object>> items = List.of(
                Map.of("id", tc2.getId().toString(), "testCaseName", "TC 2 Patched"),
                Map.of("id", tc1.getId().toString(), "data", Map.of("a", "updated", "c", "new")));

        ResponseEntity<List<TestCaseResponseDto>> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PATCH,
                jsonEntity(items),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<TestCaseResponseDto> body = response.getBody();
        assertThat(body).hasSize(2);

        // Order matches input
        assertThat(body.get(0).getId()).isEqualTo(tc2.getId());
        assertThat(body.get(0).getTestCaseName()).isEqualTo("TC 2 Patched");

        assertThat(body.get(1).getId()).isEqualTo(tc1.getId());
        assertThat(body.get(1).getData()).containsEntry("a", "updated");
        assertThat(body.get(1).getData()).containsEntry("b", "2"); // preserved
        assertThat(body.get(1).getData()).containsEntry("c", "new"); // added
    }

    @Test
    @DisplayName("Should return ordered response matching input order")
    void shouldReturnOrderedResponse() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc1 = createTestCase(suite.getId(), "A");
        TestCaseResponseDto tc2 = createTestCase(suite.getId(), "B");
        TestCaseResponseDto tc3 = createTestCase(suite.getId(), "C");

        // Request in reverse order — patch a no-op field per id (testCaseName: same value) to
        // exercise ordering semantics without changing fixture state.
        List<Map<String, Object>> items = List.of(
                Map.of("id", tc3.getId().toString(), "testCaseName", "C"),
                Map.of("id", tc1.getId().toString(), "testCaseName", "A"),
                Map.of("id", tc2.getId().toString(), "testCaseName", "B"));

        ResponseEntity<List<TestCaseResponseDto>> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PATCH,
                jsonEntity(items),
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get(0).getId()).isEqualTo(tc3.getId());
        assertThat(response.getBody().get(1).getId()).isEqualTo(tc1.getId());
        assertThat(response.getBody().get(2).getId()).isEqualTo(tc2.getId());
    }

    // ---- 8.2: Error tests ----

    @Test
    @DisplayName("Should return 400 for empty array")
    void shouldReturn400ForEmptyArray() {
        TestSuiteResponseDto suite = createTestSuite();

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PATCH,
                jsonEntity(List.of()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when exceeding max items")
    void shouldReturn400WhenExceedingMaxItems() {
        TestSuiteResponseDto suite = createTestSuite();

        List<Map<String, Object>> items = java.util.stream.IntStream.rangeClosed(1, 257)
                .mapToObj(i -> Map.<String, Object>of("id", UUID.randomUUID().toString()))
                .toList();

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PATCH,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 for duplicate IDs")
    void shouldReturn400ForDuplicateIds() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC");
        String id = tc.getId().toString();

        List<Map<String, Object>> items =
                List.of(Map.of("id", id, "testCaseName", "A"), Map.of("id", id, "testCaseName", "B"));

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PATCH,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 for missing id field")
    void shouldReturn400ForMissingIdField() {
        TestSuiteResponseDto suite = createTestSuite();

        List<Map<String, Object>> items = List.of(Map.of("testCaseName", "No ID provided"));

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PATCH,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 for invalid UUID format")
    void shouldReturn400ForInvalidUuidFormat() {
        TestSuiteResponseDto suite = createTestSuite();

        List<Map<String, Object>> items = List.of(Map.of("id", "not-a-uuid", "testCaseName", "X"));

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PATCH,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 404 for non-existent test case")
    void shouldReturn404ForNonExistentTestCase() {
        TestSuiteResponseDto suite = createTestSuite();

        List<Map<String, Object>> items = List.of(Map.of("id", UUID.randomUUID().toString(), "testCaseName", "X"));

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PATCH,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---- 8.3: Uniqueness tests ----

    @Test
    @DisplayName("Should return 409 for duplicate names within batch")
    void shouldReturn409ForDuplicateNamesWithinBatch() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc1 = createTestCase(suite.getId(), "TC 1");
        TestCaseResponseDto tc2 = createTestCase(suite.getId(), "TC 2");

        List<Map<String, Object>> items = List.of(
                Map.of("id", tc1.getId().toString(), "testCaseName", "Clash"),
                Map.of("id", tc2.getId().toString(), "testCaseName", "clash"));

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PATCH,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Should return 409 when name collides with existing test case outside batch")
    void shouldReturn409ForNameCollisionWithExisting() {
        TestSuiteResponseDto suite = createTestSuite();
        createTestCase(suite.getId(), "Existing");
        TestCaseResponseDto tc = createTestCase(suite.getId(), "Mine");

        List<Map<String, Object>> items = List.of(Map.of("id", tc.getId().toString(), "testCaseName", "Existing"));

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PATCH,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Should return 409 when item renames to current name of another batch item not changing name")
    void shouldReturn409WhenRenamingToUnchangedBatchItemName() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc1 = createTestCase(suite.getId(), "Alpha");
        TestCaseResponseDto tc2 = createTestCase(suite.getId(), "Beta");

        // tc1 renames to "Beta", tc2 only changes data (keeps "Beta")
        List<Map<String, Object>> items = List.of(
                Map.of("id", tc1.getId().toString(), "testCaseName", "Beta"),
                Map.of("id", tc2.getId().toString(), "data", Map.of("x", "y")));

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PATCH,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ---- 8.3b: Name permutation (issue #95) ----

    @Test
    @DisplayName("Should swap two test case names via batch PATCH")
    void shouldSwapNamesViaBatchPatch() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc1 = createTestCase(suite.getId(), "A");
        TestCaseResponseDto tc2 = createTestCase(suite.getId(), "B");

        List<Map<String, Object>> items = List.of(
                Map.of("id", tc1.getId().toString(), "testCaseName", "B"),
                Map.of("id", tc2.getId().toString(), "testCaseName", "A"));

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PATCH,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getName(suite, tc1.getId())).isEqualTo("B");
        assertThat(getName(suite, tc2.getId())).isEqualTo("A");
    }

    @Test
    @DisplayName("Should apply a multi-way rename cycle via batch PATCH")
    void shouldApplyMultiWayRenameCycle() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tcA = createTestCase(suite.getId(), "A");
        TestCaseResponseDto tcB = createTestCase(suite.getId(), "B");
        TestCaseResponseDto tcC = createTestCase(suite.getId(), "C");

        // Rotate: A->B, B->C, C->A
        List<Map<String, Object>> items = List.of(
                Map.of("id", tcA.getId().toString(), "testCaseName", "B"),
                Map.of("id", tcB.getId().toString(), "testCaseName", "C"),
                Map.of("id", tcC.getId().toString(), "testCaseName", "A"));

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PATCH,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getName(suite, tcA.getId())).isEqualTo("B");
        assertThat(getName(suite, tcB.getId())).isEqualTo("C");
        assertThat(getName(suite, tcC.getId())).isEqualTo("A");
    }

    private String getName(TestSuiteResponseDto suite, UUID id) {
        return restTemplate
                .getForEntity(
                        apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases/" + id), TestCaseResponseDto.class)
                .getBody()
                .getTestCaseName();
    }

    // ---- 8.4: Atomicity test ----

    @Test
    @DisplayName("Should roll back all changes when one item fails")
    void shouldRollBackAllChangesOnFailure() {
        TestSuiteResponseDto suite = createTestSuite();
        createTestCase(suite.getId(), "Blocker");
        TestCaseResponseDto tc1 = createTestCase(suite.getId(), "TC 1");
        TestCaseResponseDto tc2 = createTestCase(suite.getId(), "TC 2");

        List<Map<String, Object>> items = List.of(
                Map.of("id", tc1.getId().toString(), "testCaseName", "TC 1 Renamed"),
                Map.of("id", tc2.getId().toString(), "testCaseName", "Blocker") // collision
                );

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases"),
                HttpMethod.PATCH,
                jsonEntity(items),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Verify tc1 was NOT updated (rolled back)
        ResponseEntity<TestCaseResponseDto> tc1After = restTemplate.getForEntity(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases/" + tc1.getId()), TestCaseResponseDto.class);
        assertThat(tc1After.getBody().getTestCaseName()).isEqualTo("TC 1");
    }
}
