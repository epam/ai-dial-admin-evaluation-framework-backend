package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetDetachRequestDto;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Dataset Detach Functional Tests")
public abstract class DatasetDetachFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private TestSuiteRepository testSuiteRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName(
            "POST /test-suites/{id}/detach-dataset with no name → 200; new PRIVATE dataset with derived name, original PUBLIC dataset unchanged, test cases copied")
    void shouldDetachFromPublicDatasetWithDerivedName() {
        Dataset publicDs =
                metaTestDataHelper.createDataset("Detach-Source-" + UUID.randomUUID(), "[]", DatasetVisibility.PUBLIC);
        TestSuite suite = metaTestDataHelper.createTestSuite("Detach-Suite-" + UUID.randomUUID(), publicDs.getId());
        metaTestDataHelper.seedManyTestCasesInDataset(publicDs.getId(), 3, true);

        ResponseEntity<TestSuiteResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/detach-dataset"),
                jsonEntity(DatasetDetachRequestDto.builder().build()),
                TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        UUID newDatasetId = response.getBody().getDatasetId();
        assertThat(newDatasetId).isNotNull().isNotEqualTo(publicDs.getId());

        TestSuite refreshedSuite = testSuiteRepository.findById(suite.getId()).orElseThrow();
        assertThat(refreshedSuite.getDatasetId()).isEqualTo(newDatasetId);

        Dataset newDataset = datasetRepository.findById(newDatasetId).orElseThrow();
        assertThat(newDataset.getVisibility()).isEqualTo(DatasetVisibility.PRIVATE);
        assertThat(newDataset.getName()).contains("(clone)");

        assertThat(datasetRepository.findById(publicDs.getId()))
                .as("original PUBLIC dataset must be unchanged")
                .isPresent();
        assertThat(datasetRepository.findById(publicDs.getId()).orElseThrow().getVisibility())
                .isEqualTo(DatasetVisibility.PUBLIC);

        assertThat(testCaseRepository.countByDatasetId(newDatasetId))
                .as("test cases must be copied to the new PRIVATE dataset")
                .isEqualTo(3);
    }

    @Test
    @DisplayName(
            "POST /test-suites/{id}/detach-dataset with explicit name → 200; new PRIVATE dataset has provided name")
    void shouldDetachFromPublicDatasetWithCustomName() {
        Dataset publicDs = metaTestDataHelper.createDataset(
                "Detach-Named-Source-" + UUID.randomUUID(), "[]", DatasetVisibility.PUBLIC);
        TestSuite suite =
                metaTestDataHelper.createTestSuite("Detach-Named-Suite-" + UUID.randomUUID(), publicDs.getId());
        String customName = "My Private Clone " + UUID.randomUUID();

        ResponseEntity<TestSuiteResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/detach-dataset"),
                jsonEntity(DatasetDetachRequestDto.builder().name(customName).build()),
                TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();

        UUID newDatasetId = response.getBody().getDatasetId();
        assertThat(newDatasetId).isNotNull().isNotEqualTo(publicDs.getId());

        Dataset newDataset = datasetRepository.findById(newDatasetId).orElseThrow();
        assertThat(newDataset.getVisibility()).isEqualTo(DatasetVisibility.PRIVATE);
        assertThat(newDataset.getName()).isEqualTo(customName);
    }

    @Test
    @DisplayName("POST /test-suites/{id}/detach-dataset when suite is bound to PRIVATE dataset → 409")
    void shouldReturn409WhenSuiteIsBoundToPrivateDataset() {
        Dataset privateDs = metaTestDataHelper.createDataset(
                "Detach-Private-Source-" + UUID.randomUUID(), "[]", DatasetVisibility.PRIVATE);
        TestSuite suite =
                metaTestDataHelper.createTestSuite("Detach-Private-Suite-" + UUID.randomUUID(), privateDs.getId());

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/detach-dataset"),
                jsonEntity(DatasetDetachRequestDto.builder().build()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("PRIVATE_DATASET_REBIND_FORBIDDEN");
    }

    @Test
    @DisplayName("POST /test-suites/{id}/detach-dataset when suite has no bound dataset → 409")
    void shouldReturn409WhenSuiteHasNoDataset() {
        TestSuite suite = metaTestDataHelper.createTestSuite("Detach-Unbound-Suite-" + UUID.randomUUID(), null);

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/detach-dataset"),
                jsonEntity(DatasetDetachRequestDto.builder().build()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("SUITE_HAS_NO_DATASET");
    }

    @Test
    @DisplayName("POST /test-suites/{id}/detach-dataset with unknown suite ID → 404")
    void shouldReturn404WhenSuiteNotFound() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + UUID.randomUUID() + "/detach-dataset"),
                jsonEntity(DatasetDetachRequestDto.builder().build()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName(
            "POST /test-suites/{id}/detach-dataset remaps the suite's disabledTestCaseIds to the cloned test-case IDs")
    void shouldRemapDisabledTestCaseIdsToClonedIds() {
        Dataset publicDs = metaTestDataHelper.createDataset(
                "Detach-Remap-Source-" + UUID.randomUUID(), "[]", DatasetVisibility.PUBLIC);
        TestSuite suite =
                metaTestDataHelper.createTestSuite("Detach-Remap-Suite-" + UUID.randomUUID(), publicDs.getId());
        List<UUID> sourceIds = metaTestDataHelper.seedManyTestCasesInDataset(publicDs.getId(), 3, true);
        List<UUID> disabledSourceIds = sourceIds.subList(0, 2);
        metaTestDataHelper.appendDisabledTestCaseIds(suite.getId(), disabledSourceIds);

        ResponseEntity<TestSuiteResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/detach-dataset"),
                jsonEntity(DatasetDetachRequestDto.builder().build()),
                TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID newDatasetId = response.getBody().getDatasetId();

        Set<UUID> clonedIds = testCaseRepository.findBatchByDatasetId(newDatasetId, 0, 100).stream()
                .map(TestCase::getId)
                .collect(Collectors.toSet());

        TestSuite refreshedSuite = testSuiteRepository.findById(suite.getId()).orElseThrow();
        List<UUID> remappedDisabledIds = parseUuidArray(refreshedSuite.getDisabledTestCaseIds());

        assertThat(remappedDisabledIds)
                .as("the same number of test cases stays disabled after detach")
                .hasSize(disabledSourceIds.size());
        assertThat(remappedDisabledIds)
                .as("disabled IDs must point at the cloned test cases, not the originals")
                .doesNotContainAnyElementsOf(disabledSourceIds);
        assertThat(clonedIds)
                .as("every remapped disabled ID must belong to the new PRIVATE dataset")
                .containsAll(remappedDisabledIds);
    }

    @Test
    @DisplayName(
            "POST /test-suites/{id}/detach-dataset preserves multi-turn test cases (2-turn and 3-turn) with their content and schema")
    void shouldPreserveMultiTurnTestCasesOnDetach() {
        String schemaJson = "[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":true,\"perTurn\":true}]";
        Dataset publicDs = metaTestDataHelper.createDataset(
                "Detach-MultiTurn-Source-" + UUID.randomUUID(), schemaJson, DatasetVisibility.PUBLIC);
        TestSuite suite =
                metaTestDataHelper.createTestSuite("Detach-MultiTurn-Suite-" + UUID.randomUUID(), publicDs.getId());

        String twoTurnData = "[{\"prompt\":\"Turn A1\"},{\"prompt\":\"Turn A2\"}]";
        String threeTurnData = "[{\"prompt\":\"Turn B1\"},{\"prompt\":\"Turn B2\"},{\"prompt\":\"Turn B3\"}]";
        metaTestDataHelper.seedMultiTurnTestCaseInDataset(publicDs.getId(), "Two-Turn-Case", twoTurnData);
        metaTestDataHelper.seedMultiTurnTestCaseInDataset(publicDs.getId(), "Three-Turn-Case", threeTurnData);

        ResponseEntity<TestSuiteResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/detach-dataset"),
                jsonEntity(DatasetDetachRequestDto.builder().build()),
                TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        UUID newDatasetId = response.getBody().getDatasetId();
        assertThat(newDatasetId).isNotNull().isNotEqualTo(publicDs.getId());

        Dataset newDataset = datasetRepository.findById(newDatasetId).orElseThrow();
        assertThat(objectMapper.readTree(newDataset.getTestCaseSchema()))
                .as("cloned dataset must preserve the source schema, including perTurn fields")
                .isEqualTo(objectMapper.readTree(publicDs.getTestCaseSchema()));

        List<TestCase> clonedCases = testCaseRepository.findBatchByDatasetId(newDatasetId, 0, 100);
        assertThat(clonedCases)
                .as("both multi-turn test cases must survive detachment")
                .hasSize(2);

        TestCase clonedTwoTurn = findByName(clonedCases, "Two-Turn-Case");
        TestCase clonedThreeTurn = findByName(clonedCases, "Three-Turn-Case");

        assertThat(clonedTwoTurn.isValid())
                .as("2-row multi-turn test case must remain valid after detachment")
                .isTrue();
        assertThat(clonedTwoTurn.getMultiTurnData())
                .as("2-row multi-turn data must survive detachment")
                .isNotNull();
        assertThat(objectMapper.readTree(clonedTwoTurn.getMultiTurnData()))
                .isEqualTo(objectMapper.readTree(twoTurnData));

        assertThat(clonedThreeTurn.isValid())
                .as("3-row multi-turn test case must remain valid after detachment")
                .isTrue();
        assertThat(clonedThreeTurn.getMultiTurnData())
                .as("3-row multi-turn data must survive detachment")
                .isNotNull();
        assertThat(objectMapper.readTree(clonedThreeTurn.getMultiTurnData()))
                .isEqualTo(objectMapper.readTree(threeTurnData));
    }

    private static TestCase findByName(List<TestCase> cases, String name) {
        return cases.stream()
                .filter(tc -> tc.getTestCaseName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Test case not found: " + name));
    }

    private List<UUID> parseUuidArray(String json) {
        String[] values = objectMapper.readValue(json, String[].class);
        return Arrays.stream(values).map(UUID::fromString).collect(Collectors.toList());
    }
}
