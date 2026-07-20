package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkPatchResponseDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("TestCase Composite Bulk PATCH Functional Tests")
public abstract class TestCaseBulkPatchFunctionalTests extends BaseTestCaseBulkPatchFunctionalTests {

    @Test
    @DisplayName("Should apply composite bulk and item operations atomically")
    void shouldApplyCompositeAtomically() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc1 = createTestCase(suite.getId(), "TC 1", true);
        TestCaseResponseDto tc2 = createTestCase(suite.getId(), "TC 2", true);
        TestCaseResponseDto tc3 = createTestCase(suite.getId(), "TC 3", true);

        // Bulk: set data.tag = "x" on all rows in the dataset.
        // Item ops: rename tc1, override tc2's tag to "y" (last-writer-wins).
        Map<String, Object> body = Map.of(
                "bulkOperations",
                        List.of(Map.of(
                                "selector", Map.of("filter", List.of()),
                                "patch", Map.of("data", Map.of("tag", "x")))),
                "itemOperations",
                        List.of(
                                Map.of("id", tc1.getId().toString(), "patch", Map.of("testCaseName", "TC 1 Renamed")),
                                Map.of("id", tc2.getId().toString(), "patch", Map.of("data", Map.of("tag", "y")))));

        ResponseEntity<TestCaseBulkPatchResponseDto> response = restTemplate.exchange(
                bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), TestCaseBulkPatchResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getBulkResults()).hasSize(1);
        assertThat(response.getBody().getBulkResults().get(0).getMatched()).isEqualTo(3);
        assertThat(response.getBody().getBulkResults().get(0).getUpdated()).isEqualTo(3);
        assertThat(response.getBody().getItemResults()).hasSize(2);

        // Final state: tc1 renamed + has bulk-applied tag, tc2's tag overridden to "y", tc3 has bulk tag.
        assertThat(getTestCase(suite, tc1.getId()).getTestCaseName()).isEqualTo("TC 1 Renamed");
        assertThat(getTestCase(suite, tc1.getId()).getData()).containsEntry("tag", "x");
        assertThat(getTestCase(suite, tc2.getId()).getData()).containsEntry("tag", "y");
        assertThat(getTestCase(suite, tc3.getId()).getData()).containsEntry("tag", "x");
    }

    @Test
    @DisplayName("Should accept bulk-only request")
    void shouldAcceptBulkOnly() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc1 = createTestCase(suite.getId(), "TC 1", true);
        TestCaseResponseDto tc2 = createTestCase(suite.getId(), "TC 2", true);

        Map<String, Object> body = Map.of(
                "bulkOperations",
                List.of(Map.of(
                        "selector",
                                Map.of(
                                        "ids",
                                        List.of(
                                                tc1.getId().toString(),
                                                tc2.getId().toString())),
                        "patch", Map.of("data", Map.of("flag", "set")))));

        ResponseEntity<TestCaseBulkPatchResponseDto> response = restTemplate.exchange(
                bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), TestCaseBulkPatchResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getBulkResults().get(0).getMatched()).isEqualTo(2);
        assertThat(response.getBody().getItemResults()).isEmpty();
    }

    @Test
    @DisplayName("Should accept item-only request")
    void shouldAcceptItemOnly() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC 1", true);

        Map<String, Object> body = Map.of(
                "itemOperations",
                List.of(Map.of(
                        "id", tc.getId().toString(),
                        "patch", Map.of("testCaseName", "Renamed"))));

        ResponseEntity<TestCaseBulkPatchResponseDto> response = restTemplate.exchange(
                bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), TestCaseBulkPatchResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getBulkResults()).isEmpty();
        assertThat(response.getBody().getItemResults()).hasSize(1);
        assertThat(response.getBody().getItemResults().get(0).isUpdated()).isTrue();
    }

    @Test
    @DisplayName("Should return 400 for empty body")
    void shouldReturn400ForEmptyBody() {
        TestSuiteResponseDto suite = createTestSuite();

        Map<String, Object> body = Map.of("bulkOperations", List.of(), "itemOperations", List.of());
        ResponseEntity<String> response =
                restTemplate.exchange(bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when itemOperations exceeds max-item-operations")
    void shouldReturn400WhenItemOpsExceedMax() {
        TestSuiteResponseDto suite = createTestSuite();
        List<Map<String, Object>> ops = java.util.stream.IntStream.range(0, 501)
                .mapToObj(i -> (Map<String, Object>) Map.<String, Object>of(
                        "id", UUID.randomUUID().toString(),
                        "patch", Map.of("testCaseName", "TC " + i)))
                .toList();
        Map<String, Object> body = Map.of("itemOperations", ops);

        ResponseEntity<String> response =
                restTemplate.exchange(bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when selector.ids exceeds max-ids-per-selector")
    void shouldReturn400WhenSelectorIdsExceedMax() {
        TestSuiteResponseDto suite = createTestSuite();
        List<String> tooMany = java.util.stream.IntStream.range(0, 10001)
                .mapToObj(i -> UUID.randomUUID().toString())
                .toList();
        Map<String, Object> body = Map.of(
                "bulkOperations",
                List.of(Map.of(
                        "selector", Map.of("ids", tooMany),
                        "patch", Map.of("data", Map.of("flag", "set")))));

        ResponseEntity<String> response =
                restTemplate.exchange(bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 for non-whitelisted bulk patch field")
    void shouldReturn400ForNonWhitelistedField() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC 1", true);

        // Bulk-patch whitelist is currently {testCaseName, data}; any other field is rejected.
        // "valid" is a system-managed flag that cannot be patched via the bulk endpoint.
        Map<String, Object> body = Map.of(
                "bulkOperations",
                List.of(Map.of(
                        "selector", Map.of("ids", List.of(tc.getId().toString())),
                        "patch", Map.of("valid", false))));

        ResponseEntity<String> response =
                restTemplate.exchange(bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 for selector with both ids and filter")
    void shouldReturn400WhenSelectorHasBothVariants() {
        TestSuiteResponseDto suite = createTestSuite();

        Map<String, Object> body = Map.of(
                "bulkOperations",
                List.of(Map.of(
                        "selector",
                                Map.of(
                                        "ids",
                                        List.of(UUID.randomUUID().toString()),
                                        "filter",
                                        List.of("testCaseName:like:x")),
                        "patch", Map.of("data", Map.of("flag", "set")))));

        ResponseEntity<String> response =
                restTemplate.exchange(bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 for duplicate id within itemOperations")
    void shouldReturn400ForDuplicateItemIds() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC 1", true);
        String id = tc.getId().toString();

        Map<String, Object> body = Map.of(
                "itemOperations",
                List.of(
                        Map.of("id", id, "patch", Map.of("testCaseName", "A")),
                        Map.of("id", id, "patch", Map.of("testCaseName", "B"))));

        ResponseEntity<String> response =
                restTemplate.exchange(bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 404 when selector ids include cross-dataset id")
    void shouldReturn404ForCrossDatasetId() {
        TestSuiteResponseDto suiteA = createTestSuite();
        TestSuiteResponseDto suiteB = createTestSuite();
        TestCaseResponseDto tcInB = createTestCase(suiteB.getId(), "Other dataset", true);

        Map<String, Object> body = Map.of(
                "bulkOperations",
                List.of(Map.of(
                        "selector", Map.of("ids", List.of(tcInB.getId().toString())),
                        "patch", Map.of("data", Map.of("flag", "set")))));

        ResponseEntity<String> response =
                restTemplate.exchange(bulkUrl(suiteA.getId()), HttpMethod.PATCH, jsonEntity(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should resolve empty filter selector to all rows in the dataset")
    void shouldResolveEmptyFilterToAllRowsInDataset() {
        TestSuiteResponseDto suite = createTestSuite();
        seedManyTestCases(suite.getId(), 5, true);
        Map<String, Object> body = Map.of(
                "bulkOperations",
                List.of(Map.of(
                        "selector", Map.of("filter", List.of()),
                        "patch", Map.of("data", Map.of("touched", true)))));
        ResponseEntity<TestCaseBulkPatchResponseDto> response = restTemplate.exchange(
                bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), TestCaseBulkPatchResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getBulkResults().get(0).getMatched()).isEqualTo(5);
    }

    @Test
    @DisplayName("Should report updated = matched - K when subset already matches state")
    void shouldReportNoOpUpdatedCount() {
        TestSuiteResponseDto suite = createTestSuite();
        // Pre-seed two rows with data={tag:"x"} (the value the bulk op will set) and one with
        // data={tag:"other"} so only that third row's state actually changes.
        UUID datasetId = suite.getDatasetId();
        UUID m1 = createCaseWithData(datasetId, "Matches state 1", Map.of("tag", "x"))
                .getId();
        UUID m2 = createCaseWithData(datasetId, "Matches state 2", Map.of("tag", "x"))
                .getId();
        UUID other =
                createCaseWithData(datasetId, "Differs", Map.of("tag", "other")).getId();

        Map<String, Object> body = Map.of(
                "bulkOperations",
                List.of(Map.of(
                        "selector", Map.of("ids", List.of(m1.toString(), m2.toString(), other.toString())),
                        "patch", Map.of("data", Map.of("tag", "x")))));

        ResponseEntity<TestCaseBulkPatchResponseDto> response = restTemplate.exchange(
                bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), TestCaseBulkPatchResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getBulkResults().get(0).getMatched()).isEqualTo(3);
        // Two were already in the target state (no-op); only the third row's state actually changes.
        assertThat(response.getBody().getBulkResults().get(0).getUpdated()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should patch 10000 seeded rows in a single bulk op")
    void shouldPatchLargeDataset() {
        TestSuiteResponseDto suite = createTestSuite();
        List<UUID> ids = seedManyTestCases(suite.getId(), 10000, true);

        Map<String, Object> body = Map.of(
                "bulkOperations",
                List.of(Map.of(
                        "selector", Map.of("filter", List.of()),
                        "patch", Map.of("data", Map.of("batch", "1")))));

        ResponseEntity<TestCaseBulkPatchResponseDto> response = restTemplate.exchange(
                bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), TestCaseBulkPatchResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getBulkResults().get(0).getMatched()).isEqualTo(10000);
        assertThat(response.getBody().getBulkResults().get(0).getUpdated()).isEqualTo(10000);

        // Spot-check three rows spanning the seeded range received the bulk-patched data field.
        UUID datasetId = suite.getDatasetId();
        UUID first = ids.get(0);
        UUID middle = ids.get(5000);
        UUID last = ids.get(9999);
        assertThat(testCaseRepository
                        .findByIdAndDatasetId(first, datasetId)
                        .orElseThrow()
                        .getData())
                .contains("\"batch\"")
                .contains("\"1\"");
        assertThat(testCaseRepository
                        .findByIdAndDatasetId(middle, datasetId)
                        .orElseThrow()
                        .getData())
                .contains("\"batch\"")
                .contains("\"1\"");
        assertThat(testCaseRepository
                        .findByIdAndDatasetId(last, datasetId)
                        .orElseThrow()
                        .getData())
                .contains("\"batch\"")
                .contains("\"1\"");
    }

    @Test
    @DisplayName("Should roll back all changes when an item op causes name collision (409)")
    void shouldRollBackOnItemNameCollision() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto blocker = createTestCase(suite.getId(), "Blocker", true);
        TestCaseResponseDto tc1 = createTestCase(suite.getId(), "TC 1", true);

        Map<String, Object> body = Map.of(
                "bulkOperations",
                        List.of(Map.of(
                                "selector", Map.of("ids", List.of(tc1.getId().toString())),
                                "patch", Map.of("data", Map.of("flag", "set")))),
                "itemOperations",
                        List.of(Map.of(
                                "id", tc1.getId().toString(),
                                "patch", Map.of("testCaseName", "Blocker"))));

        ResponseEntity<String> response =
                restTemplate.exchange(bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Bulk op was rolled back: tc1's data still empty, no flag set.
        assertThat(getTestCase(suite, tc1.getId()).getData()).doesNotContainKey("flag");
        assertThat(getTestCase(suite, tc1.getId()).getTestCaseName()).isEqualTo("TC 1");
        assertThat(getTestCase(suite, blocker.getId()).getTestCaseName()).isEqualTo("Blocker");
    }

    @Test
    @DisplayName("Should let item operation override field set by prior bulk operation (last-writer-wins)")
    void shouldApplyItemOverrideAfterBulk() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "TC", true);

        // Bulk sets data.tag="x" everywhere; item op overrides this single row's tag to "y".
        Map<String, Object> body = Map.of(
                "bulkOperations",
                        List.of(Map.of(
                                "selector", Map.of("filter", List.of()),
                                "patch", Map.of("data", Map.of("tag", "x")))),
                "itemOperations",
                        List.of(Map.of(
                                "id", tc.getId().toString(),
                                "patch", Map.of("data", Map.of("tag", "y")))));

        ResponseEntity<TestCaseBulkPatchResponseDto> response = restTemplate.exchange(
                bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), TestCaseBulkPatchResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getTestCase(suite, tc.getId()).getData()).containsEntry("tag", "y");
    }

    private TestCaseResponseDto createCaseWithData(UUID datasetId, String name, Map<String, Object> data) {
        Map<String, Object> body = Map.of(
                "testCaseName", name,
                "data", data);
        ResponseEntity<TestCaseResponseDto> r = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"), jsonEntity(body), TestCaseResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    @Test
    @DisplayName("Should swap two test case names via composite bulk patch itemOperations")
    void shouldSwapNamesViaItemOperations() {
        TestSuiteResponseDto suite = createTestSuite();
        TestCaseResponseDto tc1 = createTestCase(suite.getId(), "A", true);
        TestCaseResponseDto tc2 = createTestCase(suite.getId(), "B", true);

        Map<String, Object> body = Map.of(
                "itemOperations",
                List.of(
                        Map.of("id", tc1.getId().toString(), "patch", Map.of("testCaseName", "B")),
                        Map.of("id", tc2.getId().toString(), "patch", Map.of("testCaseName", "A"))));

        ResponseEntity<TestCaseBulkPatchResponseDto> response = restTemplate.exchange(
                bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), TestCaseBulkPatchResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getTestCase(suite, tc1.getId()).getTestCaseName()).isEqualTo("B");
        assertThat(getTestCase(suite, tc2.getId()).getTestCaseName()).isEqualTo("A");
    }

    private TestCaseResponseDto getTestCase(TestSuiteResponseDto suite, UUID id) {
        ResponseEntity<TestCaseResponseDto> resp = restTemplate.getForEntity(
                apiUrl("/datasets/" + suite.getDatasetId() + "/test-cases/" + id), TestCaseResponseDto.class);
        return resp.getBody();
    }
}
