package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.DatasetCloneRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Dataset Clone Functional Tests")
public abstract class DatasetCloneFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName(
            "POST /datasets/{id}/clone with empty body → 201; new id, derived name, inherited PUBLIC visibility, copied test cases, ETag")
    void shouldCloneWithDerivedName() {
        Dataset source =
                metaTestDataHelper.createDataset("Clone-Source-" + UUID.randomUUID(), "[]", DatasetVisibility.PUBLIC);
        metaTestDataHelper.seedManyTestCasesInDataset(source.getId(), 3, true);

        ResponseEntity<DatasetResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + source.getId() + "/clone"),
                jsonEntity(new DatasetCloneRequestDto()),
                DatasetResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getHeaders().getETag()).isNotBlank();

        UUID newId = response.getBody().getId();
        assertThat(newId).isNotNull().isNotEqualTo(source.getId());
        assertThat(response.getBody().getVisibility()).isEqualTo(DatasetVisibility.PUBLIC);
        assertThat(response.getBody().getName()).contains("(clone)");

        assertThat(testCaseRepository.countByDatasetId(newId))
                .as("test cases must be copied to the clone")
                .isEqualTo(3);

        assertThat(datasetRepository.findById(source.getId()))
                .as("source dataset must be unchanged")
                .isPresent();
    }

    @Test
    @DisplayName("POST /datasets/{id}/clone with explicit name and description → 201; clone uses both verbatim")
    void shouldCloneWithExplicitNameAndDescription() {
        Dataset source = metaTestDataHelper.createDataset(
                "Clone-Named-Source-" + UUID.randomUUID(), "[]", DatasetVisibility.PUBLIC, "original description");
        String customName = "My Clone " + UUID.randomUUID();

        ResponseEntity<DatasetResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + source.getId() + "/clone"),
                jsonEntity(DatasetCloneRequestDto.builder()
                        .name(customName)
                        .description("overridden description")
                        .build()),
                DatasetResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getName()).isEqualTo(customName);
        assertThat(response.getBody().getDescription()).isEqualTo("overridden description");
    }

    @Test
    @DisplayName("POST /datasets/{id}/clone without description → 201; clone inherits the source description")
    void shouldCloneInheritingSourceDescription() {
        Dataset source = metaTestDataHelper.createDataset(
                "Clone-Desc-Source-" + UUID.randomUUID(), "[]", DatasetVisibility.PUBLIC, "inherited description");

        ResponseEntity<DatasetResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + source.getId() + "/clone"),
                jsonEntity(new DatasetCloneRequestDto()),
                DatasetResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDescription()).isEqualTo("inherited description");
    }

    @Test
    @DisplayName("POST /datasets/{id}/clone twice without a name → second clone gets the '(clone 2)' suffix")
    void shouldDeriveNumberedSuffixOnCollision() {
        Dataset source = metaTestDataHelper.createDataset(
                "Clone-Dedup-Src-" + UUID.randomUUID(), "[]", DatasetVisibility.PUBLIC);

        ResponseEntity<DatasetResponseDto> first = restTemplate.postForEntity(
                apiUrl("/datasets/" + source.getId() + "/clone"),
                jsonEntity(new DatasetCloneRequestDto()),
                DatasetResponseDto.class);
        ResponseEntity<DatasetResponseDto> second = restTemplate.postForEntity(
                apiUrl("/datasets/" + source.getId() + "/clone"),
                jsonEntity(new DatasetCloneRequestDto()),
                DatasetResponseDto.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().getName()).endsWith("(clone)");
        assertThat(second.getBody().getName()).endsWith("(clone 2)");
    }

    @Test
    @DisplayName("POST /datasets/{id}/clone with an explicit name that already exists → 409")
    void shouldReturn409OnDuplicateName() {
        Dataset source =
                metaTestDataHelper.createDataset("Clone-Dup-Src-" + UUID.randomUUID(), "[]", DatasetVisibility.PUBLIC);
        Dataset other = metaTestDataHelper.createDataset(
                "Clone-Dup-Existing-" + UUID.randomUUID(), "[]", DatasetVisibility.PUBLIC);

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + source.getId() + "/clone"),
                jsonEntity(
                        DatasetCloneRequestDto.builder().name(other.getName()).build()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("POST /datasets/{id}/clone with an unknown source ID → 404")
    void shouldReturn404WhenSourceNotFound() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + UUID.randomUUID() + "/clone"),
                jsonEntity(new DatasetCloneRequestDto()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName(
            "POST /datasets/{id}/clone on a PRIVATE source → 400 PRIVATE_DATASET_REQUIRES_SUITE_BINDING; nothing persisted")
    void shouldRejectCloningPrivateDataset() {
        Dataset source = metaTestDataHelper.createDataset(
                "Clone-Private-Src-" + UUID.randomUUID(), "[]", DatasetVisibility.PRIVATE);
        long datasetsBefore = datasetRepository.count();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + source.getId() + "/clone"),
                jsonEntity(new DatasetCloneRequestDto()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("PRIVATE_DATASET_REQUIRES_SUITE_BINDING");
        assertThat(datasetRepository.count())
                .as("a rejected clone must not persist a new dataset")
                .isEqualTo(datasetsBefore);
    }

    @Test
    @DisplayName(
            "POST /datasets/{id}/clone preserves multi-turn test cases (2-turn and 3-turn) with their content and schema")
    void shouldPreserveMultiTurnTestCasesOnClone() {
        String schemaJson = "[{\"name\":\"prompt\",\"type\":\"STRING\",\"required\":true,\"perTurn\":true}]";
        Dataset source = metaTestDataHelper.createDataset(
                "Clone-MultiTurn-Source-" + UUID.randomUUID(), schemaJson, DatasetVisibility.PUBLIC);

        String twoTurnData = "[{\"prompt\":\"Turn A1\"},{\"prompt\":\"Turn A2\"}]";
        String threeTurnData = "[{\"prompt\":\"Turn B1\"},{\"prompt\":\"Turn B2\"},{\"prompt\":\"Turn B3\"}]";
        metaTestDataHelper.seedMultiTurnTestCaseInDataset(source.getId(), "Two-Turn-Case", twoTurnData);
        metaTestDataHelper.seedMultiTurnTestCaseInDataset(source.getId(), "Three-Turn-Case", threeTurnData);

        ResponseEntity<DatasetResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + source.getId() + "/clone"),
                jsonEntity(new DatasetCloneRequestDto()),
                DatasetResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        UUID newId = response.getBody().getId();
        assertThat(newId).isNotNull().isNotEqualTo(source.getId());

        Dataset clonedDataset = datasetRepository.findById(newId).orElseThrow();
        assertThat(objectMapper.readTree(clonedDataset.getTestCaseSchema()))
                .as("cloned dataset must preserve the source schema, including perTurn fields")
                .isEqualTo(objectMapper.readTree(source.getTestCaseSchema()));

        List<TestCase> clonedCases = testCaseRepository.findBatchByDatasetId(newId, 0, 100);
        assertThat(clonedCases)
                .as("both multi-turn test cases must survive cloning")
                .hasSize(2);

        TestCase clonedTwoTurn = findByName(clonedCases, "Two-Turn-Case");
        TestCase clonedThreeTurn = findByName(clonedCases, "Three-Turn-Case");

        assertThat(clonedTwoTurn.isValid())
                .as("2-row multi-turn test case must remain valid after cloning")
                .isTrue();
        assertThat(clonedTwoTurn.getMultiTurnData())
                .as("2-row multi-turn data must survive cloning")
                .isNotNull();
        assertThat(objectMapper.readTree(clonedTwoTurn.getMultiTurnData()))
                .isEqualTo(objectMapper.readTree(twoTurnData));

        assertThat(clonedThreeTurn.isValid())
                .as("3-row multi-turn test case must remain valid after cloning")
                .isTrue();
        assertThat(clonedThreeTurn.getMultiTurnData())
                .as("3-row multi-turn data must survive cloning")
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
}
