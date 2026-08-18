package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Cap-violation scenarios for composite bulk PATCH that need lowered limits to be triggerable
 * without seeding tens of thousands of rows. The nested wrapper in {@code PostgresFunctionalTests}
 * supplies the {@code @TestPropertySource} overrides used here.
 */
@DisplayName("TestCase Composite Bulk PATCH — Cap Violation Tests")
public abstract class TestCaseBulkPatchCapsFunctionalTests extends BaseTestCaseBulkPatchFunctionalTests {

    @Test
    @DisplayName("Should return 400 when filter selector matches more than max-ids-per-selector")
    void shouldReturn400WhenFilterSelectorOverMatches() {
        TestSuiteResponseDto suite = createTestSuite();
        // Lowered cap is 3 (see PostgresFunctionalTests nested wrapper); seed 4 rows so an
        // empty-filter selector resolves to a set strictly larger than the cap.
        seedManyTestCases(suite.getId(), 4);

        Map<String, Object> body = Map.of(
                "bulkOperations",
                List.of(Map.of(
                        "selector", Map.of("filter", List.of()),
                        "patch", Map.of("data", Map.of("flag", "x")))));

        ResponseEntity<String> response =
                restTemplate.exchange(bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("max-ids-per-selector");
    }

    @Test
    @DisplayName("Should return 400 when combined op count exceeds max-operations")
    void shouldReturn400WhenCombinedOpCountExceedsMaxOperations() {
        TestSuiteResponseDto suite = createTestSuite();
        seedManyTestCases(suite.getId(), 1);

        // Lowered cap is 3 (see PostgresFunctionalTests nested wrapper). Send 4 ops total to trip it.
        List<Map<String, Object>> bulkOps = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            bulkOps.add(Map.of(
                    "selector", Map.of("ids", List.of(UUID.randomUUID().toString())),
                    "patch", Map.of("data", Map.of("flag", "x"))));
        }
        Map<String, Object> body = Map.of("bulkOperations", bulkOps);

        ResponseEntity<String> response =
                restTemplate.exchange(bulkUrl(suite.getId()), HttpMethod.PATCH, jsonEntity(body), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Combined op count");
    }
}
