package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.DatasetRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.service.domain.dto.RevalidationStatus;
import com.epam.aidial.evaluation.service.domain.dto.RevalidationTaskDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("Revalidation Coercion Functional Tests")
public abstract class RevalidationCoercionFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("revcoerce-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    @Test
    @DisplayName("Boolean→STRING is auto-coerced; row stays valid; coercedCellCount > 0")
    void booleanToStringHappyPath() {
        TestSuiteResponseDto suite = createSuiteWithSchema(List.of(fieldDef("flag", SchemaFieldType.BOOLEAN, false)));
        TestCaseResponseDto tc = createTestCase(suite.getId(), "tc-bool", Map.of("flag", true));
        assertThat(tc.isValid()).isTrue();

        RevalidationTaskDto completed =
                updateSchemaAndAwait(suite, List.of(fieldDef("flag", SchemaFieldType.STRING, false)));

        assertThat(completed.getStatus()).isEqualTo(RevalidationStatus.COMPLETED);
        assertThat(completed.getValidCount()).isEqualTo(1);
        assertThat(completed.getInvalidCount()).isZero();
        assertThat(completed.getCoercedCellCount()).isEqualTo(1L);

        TestCase persisted = testCaseRepository
                .findByIdAndDatasetId(tc.getId(), metaTestDataHelper.getDatasetId(suite.getId()))
                .orElseThrow();
        assertThat(persisted.isValid()).isTrue();
        assertThat(parseData(persisted)).containsEntry("flag", "true");
    }

    @Test
    @DisplayName("Integer→STRING is auto-coerced; row valid; cell counted")
    void integerToStringCoerces() {
        TestSuiteResponseDto suite = createSuiteWithSchema(List.of(fieldDef("year", SchemaFieldType.INTEGER, false)));
        TestCaseResponseDto tc = createTestCase(suite.getId(), "tc-int", Map.of("year", 1865L));

        RevalidationTaskDto completed =
                updateSchemaAndAwait(suite, List.of(fieldDef("year", SchemaFieldType.STRING, false)));

        assertThat(completed.getValidCount()).isEqualTo(1);
        assertThat(completed.getCoercedCellCount()).isEqualTo(1L);

        TestCase persisted = testCaseRepository
                .findByIdAndDatasetId(tc.getId(), metaTestDataHelper.getDatasetId(suite.getId()))
                .orElseThrow();
        assertThat(parseData(persisted)).containsEntry("year", "1865");
        assertThat(persisted.isValid()).isTrue();
    }

    @Test
    @DisplayName("Number→STRING is auto-coerced; row valid")
    void numberToStringCoerces() {
        TestSuiteResponseDto suite = createSuiteWithSchema(List.of(fieldDef("score", SchemaFieldType.NUMBER, false)));
        TestCaseResponseDto tc = createTestCase(suite.getId(), "tc-num", Map.of("score", 3.14));

        RevalidationTaskDto completed =
                updateSchemaAndAwait(suite, List.of(fieldDef("score", SchemaFieldType.STRING, false)));

        assertThat(completed.getValidCount()).isEqualTo(1);
        assertThat(completed.getCoercedCellCount()).isEqualTo(1L);

        TestCase persisted = testCaseRepository
                .findByIdAndDatasetId(tc.getId(), metaTestDataHelper.getDatasetId(suite.getId()))
                .orElseThrow();
        assertThat(parseData(persisted)).containsEntry("score", "3.14");
    }

    @Test
    @DisplayName("Object→STRING is NOT coerced; row marked invalid; cell NOT counted")
    void objectToStringSkipped() {
        TestSuiteResponseDto suite = createSuiteWithSchema(List.of(fieldDef("payload", SchemaFieldType.OBJECT, false)));
        TestCaseResponseDto tc = createTestCase(suite.getId(), "tc-obj", Map.of("payload", Map.of("a", 1)));

        RevalidationTaskDto completed =
                updateSchemaAndAwait(suite, List.of(fieldDef("payload", SchemaFieldType.STRING, false)));

        assertThat(completed.getInvalidCount()).isEqualTo(1);
        assertThat(completed.getValidCount()).isZero();
        assertThat(completed.getCoercedCellCount()).isZero();

        TestCase persisted = testCaseRepository
                .findByIdAndDatasetId(tc.getId(), metaTestDataHelper.getDatasetId(suite.getId()))
                .orElseThrow();
        assertThat(persisted.isValid()).isFalse();
    }

    @Test
    @DisplayName("Number→FILE is NOT coerced; row marked invalid")
    void numberToFileSkipped() {
        TestSuiteResponseDto suite =
                createSuiteWithSchema(List.of(fieldDef("attachment", SchemaFieldType.NUMBER, false)));
        TestCaseResponseDto tc = createTestCase(suite.getId(), "tc-num-file", Map.of("attachment", 42.0));

        RevalidationTaskDto completed =
                updateSchemaAndAwait(suite, List.of(fieldDef("attachment", SchemaFieldType.FILE, false)));

        assertThat(completed.getInvalidCount()).isEqualTo(1);
        assertThat(completed.getCoercedCellCount()).isZero();

        TestCase persisted = testCaseRepository
                .findByIdAndDatasetId(tc.getId(), metaTestDataHelper.getDatasetId(suite.getId()))
                .orElseThrow();
        assertThat(persisted.isValid()).isFalse();
    }

    @Test
    @DisplayName("String→BOOLEAN coerces only \"true\"/\"false\"; \"yes\" stays invalid")
    void stringToBooleanMixedRows() {
        TestSuiteResponseDto suite = createSuiteWithSchema(List.of(fieldDef("flag", SchemaFieldType.STRING, false)));
        TestCaseResponseDto coerceable = createTestCase(suite.getId(), "tc-string-true", Map.of("flag", "true"));
        TestCaseResponseDto rejected = createTestCase(suite.getId(), "tc-string-yes", Map.of("flag", "yes"));

        RevalidationTaskDto completed =
                updateSchemaAndAwait(suite, List.of(fieldDef("flag", SchemaFieldType.BOOLEAN, false)));

        assertThat(completed.getValidCount()).isEqualTo(1);
        assertThat(completed.getInvalidCount()).isEqualTo(1);
        assertThat(completed.getCoercedCellCount()).isEqualTo(1L);

        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        TestCase coerced = testCaseRepository
                .findByIdAndDatasetId(coerceable.getId(), datasetId)
                .orElseThrow();
        TestCase invalid = testCaseRepository
                .findByIdAndDatasetId(rejected.getId(), datasetId)
                .orElseThrow();
        assertThat(coerced.isValid()).isTrue();
        assertThat(parseData(coerced)).containsEntry("flag", true);
        assertThat(invalid.isValid()).isFalse();
        assertThat(parseData(invalid)).containsEntry("flag", "yes");
    }

    @Test
    @DisplayName("Fractional Double→INTEGER is NOT coerced; data unchanged; row invalid")
    void fractionalDoubleToIntegerSkipped() {
        TestSuiteResponseDto suite = createSuiteWithSchema(List.of(fieldDef("n", SchemaFieldType.NUMBER, false)));
        TestCaseResponseDto tc = createTestCase(suite.getId(), "tc-frac", Map.of("n", 3.14));

        RevalidationTaskDto completed =
                updateSchemaAndAwait(suite, List.of(fieldDef("n", SchemaFieldType.INTEGER, false)));

        assertThat(completed.getInvalidCount()).isEqualTo(1);
        assertThat(completed.getCoercedCellCount()).isZero();

        TestCase persisted = testCaseRepository
                .findByIdAndDatasetId(tc.getId(), metaTestDataHelper.getDatasetId(suite.getId()))
                .orElseThrow();
        assertThat(parseData(persisted)).containsEntry("n", 3.14);
    }

    @Test
    @DisplayName("Concurrent edit (updated_at bumped before guarded UPDATE) leaves row untouched")
    void concurrentEditGuardMissDirectRepository() {
        TestSuiteResponseDto suite = createSuiteWithSchema(List.of(fieldDef("flag", SchemaFieldType.BOOLEAN, false)));
        TestCaseResponseDto tc = createTestCase(suite.getId(), "tc-guard", Map.of("flag", true));
        TestCase preState = testCaseRepository
                .findByIdAndDatasetId(tc.getId(), metaTestDataHelper.getDatasetId(suite.getId()))
                .orElseThrow();
        long staleSeenAt = preState.getUpdatedAt() - 1000;

        int dataRows = testCaseRepository.updateDataIfUnchanged(
                tc.getId(), suite.getId(), "{\"flag\":\"true\"}", staleSeenAt, staleSeenAt + 5000);
        int validationRows = testCaseRepository.updateValidationIfUnchanged(
                tc.getId(), suite.getId(), false, "[]", staleSeenAt, staleSeenAt + 5000);

        assertThat(dataRows).isZero();
        assertThat(validationRows).isZero();

        TestCase postState = testCaseRepository
                .findByIdAndDatasetId(tc.getId(), metaTestDataHelper.getDatasetId(suite.getId()))
                .orElseThrow();
        assertThat(postState.getUpdatedAt()).isEqualTo(preState.getUpdatedAt());
        assertThat(postState.getData()).isEqualTo(preState.getData());
        assertThat(postState.isValid()).isEqualTo(preState.isValid());
    }

    @Test
    @DisplayName("Idempotency: second revalidation against already-coerced data yields coercedCellCount = 0")
    void idempotentSecondRun() {
        TestSuiteResponseDto suite = createSuiteWithSchema(List.of(fieldDef("flag", SchemaFieldType.BOOLEAN, false)));
        TestCaseResponseDto tc = createTestCase(suite.getId(), "tc-idem", Map.of("flag", true));

        RevalidationTaskDto firstRun =
                updateSchemaAndAwait(suite, List.of(fieldDef("flag", SchemaFieldType.STRING, false)));
        assertThat(firstRun.getCoercedCellCount()).isEqualTo(1L);

        TestSuiteResponseDto refreshed = getSuite(suite.getId());
        TestCase afterFirst = testCaseRepository
                .findByIdAndDatasetId(tc.getId(), metaTestDataHelper.getDatasetId(suite.getId()))
                .orElseThrow();
        long updatedAtAfterFirst = afterFirst.getUpdatedAt();

        // Second run against the same STRING schema — values already strings.
        RevalidationTaskDto secondRun = updateAndAwait(
                refreshed,
                List.of(
                        fieldDef("flag", SchemaFieldType.STRING, false),
                        fieldDef("untouched", SchemaFieldType.STRING, false)));
        assertThat(secondRun.getCoercedCellCount()).isZero();

        TestCase afterSecond = testCaseRepository
                .findByIdAndDatasetId(tc.getId(), metaTestDataHelper.getDatasetId(suite.getId()))
                .orElseThrow();
        assertThat(afterSecond.getData()).isEqualTo(afterFirst.getData());
        // updated_at_ms may have shifted because the validation guarded UPDATE still ran;
        // but data column itself must be unchanged. The coercedCellCount=0 is the strict assertion.
        assertThat(afterSecond.getUpdatedAt()).isGreaterThanOrEqualTo(updatedAtAfterFirst);
    }

    // ---------------------------------------------------------------------- helpers

    private TestSuiteResponseDto createSuiteWithSchema(List<FieldDefinitionDto> schema) {
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("Coercion Suite " + UUID.randomUUID())
                .description("Coercion test suite")
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(schema))
                .build();
        ResponseEntity<TestSuiteResponseDto> r =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private TestCaseResponseDto createTestCase(UUID suiteId, String name, Map<String, Object> data) {
        UUID datasetId = metaTestDataHelper.getDatasetId(suiteId);
        TestCaseRequestDto req =
                TestCaseRequestDto.builder().testCaseName(name).data(data).build();
        ResponseEntity<TestCaseResponseDto> r = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"), jsonEntity(req), TestCaseResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private RevalidationTaskDto updateSchemaAndAwait(TestSuiteResponseDto suite, List<FieldDefinitionDto> newSchema) {
        return updateAndAwait(suite, newSchema);
    }

    private RevalidationTaskDto updateAndAwait(TestSuiteResponseDto suite, List<FieldDefinitionDto> newSchema) {
        UUID datasetId = suite.getDatasetId();
        DatasetResponseDto dataset = getDataset(datasetId);
        DatasetRequestDto req = DatasetRequestDto.builder()
                .name(dataset.getName())
                .description(dataset.getDescription())
                .testCaseSchema(newSchema)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch(dataset.getVersion() != null ? "\"" + dataset.getVersion() + "\"" : "0");
        ResponseEntity<RevalidationTaskDto> response = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId),
                HttpMethod.PUT,
                new HttpEntity<>(req, headers),
                RevalidationTaskDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        RevalidationTaskDto task = response.getBody();
        assertThat(task).isNotNull();
        return awaitCompletion(datasetId, task.getTaskId(), 20);
    }

    private DatasetResponseDto getDataset(UUID id) {
        ResponseEntity<DatasetResponseDto> r =
                restTemplate.getForEntity(apiUrl("/datasets/" + id), DatasetResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private RevalidationTaskDto awaitCompletion(UUID datasetId, UUID taskId, int seconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<RevalidationTaskDto> r = restTemplate.getForEntity(
                    apiUrl("/datasets/" + datasetId + "/revalidation-tasks/" + taskId), RevalidationTaskDto.class);
            if (r.getStatusCode() == HttpStatus.OK && r.getBody() != null) {
                RevalidationStatus status = r.getBody().getStatus();
                if (status == RevalidationStatus.COMPLETED
                        || status == RevalidationStatus.FAILED
                        || status == RevalidationStatus.TIMED_OUT) {
                    return r.getBody();
                }
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted", e);
            }
        }
        throw new AssertionError("Revalidation did not complete in " + seconds + "s");
    }

    private TestSuiteResponseDto getSuite(UUID id) {
        ResponseEntity<TestSuiteResponseDto> r =
                restTemplate.getForEntity(apiUrl("/test-suites/" + id), TestSuiteResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody();
    }

    private static FieldDefinitionDto fieldDef(String name, SchemaFieldType type, boolean required) {
        return FieldDefinitionDto.builder()
                .name(name)
                .type(type)
                .required(required)
                .build();
    }

    private Map<String, Object> parseData(TestCase tc) {
        try {
            return objectMapper.readValue(tc.getData(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new AssertionError("Failed to parse test case data: " + tc.getData(), e);
        }
    }
}
