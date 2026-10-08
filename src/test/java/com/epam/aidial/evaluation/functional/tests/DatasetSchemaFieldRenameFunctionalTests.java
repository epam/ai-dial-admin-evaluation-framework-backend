package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.FormPartDto;
import com.epam.aidial.evaluation.runner.dto.FormPartType;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.MultipartFormDataRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.service.domain.dto.DatasetRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Dataset schema field ids — create/update/rename functional tests")
public abstract class DatasetSchemaFieldRenameFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private TestCaseRepository testCaseRepository;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("rename column2 to column3 keeps the stored value under the new name")
    void renameKeepsData() {
        final DatasetResponseDto dataset = createDataset(field("column1"), field("column2"));
        final UUID tc = seed(dataset.getId(), "{\"column1\":\"x\",\"column2\":\"y\"}");

        final ResponseEntity<String> response =
                put(dataset, List.of(withId(dataset, 0, "column1"), withId(dataset, 1, "column3")));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(dataOf(dataset.getId(), tc)).isEqualTo(Map.of("column1", "x", "column3", "y"));
    }

    @Test
    @DisplayName("swapping two field names swaps their values without losing either")
    void swapKeepsBothValues() {
        final DatasetResponseDto dataset = createDataset(field("a"), field("b"));
        final UUID tc = seed(dataset.getId(), "{\"a\":\"va\",\"b\":\"vb\"}");

        final ResponseEntity<String> response = put(dataset, List.of(withId(dataset, 0, "b"), withId(dataset, 1, "a")));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(dataOf(dataset.getId(), tc)).isEqualTo(Map.of("b", "va", "a", "vb"));
    }

    @Test
    @DisplayName("rename combined with a type change moves the value and coerces it after the PUT")
    void renameWithTypeChangeCoerces() {
        final DatasetResponseDto dataset = createDataset(typed("flag", SchemaFieldType.BOOLEAN));
        final UUID tc = seed(dataset.getId(), "{\"flag\":true}");
        final FieldDefinitionDto renamed = withId(dataset, 0, "label").toBuilder()
                .type(SchemaFieldType.STRING)
                .build();

        final ResponseEntity<String> response = put(dataset, List.of(renamed));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(dataOf(dataset.getId(), tc)).isEqualTo(Map.of("label", "true"));
    }

    @Test
    @DisplayName("rename plus perTurn flip leaves the value in data and the case reports the misplacement")
    void renameWithPerTurnFlipDoesNotRelocate() {
        final DatasetResponseDto dataset = createDataset(field("ctx"), perTurn("q"));
        final UUID tc = metaTestDataHelper.seedTestCaseInDataset(
                dataset.getId(), "tc-" + UUID.randomUUID(), "{\"ctx\":\"v\"}", "[{\"q\":\"1\"},{\"q\":\"2\"}]");
        final FieldDefinitionDto flipped =
                withId(dataset, 0, "context").toBuilder().perTurn(true).build();

        final ResponseEntity<String> response = put(dataset, List.of(flipped, withId(dataset, 1, "q")));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        final TestCase persisted =
                testCaseRepository.findByIdAndDatasetId(tc, dataset.getId()).orElseThrow();
        assertThat(parse(persisted.getData())).isEqualTo(Map.of("context", "v"));
        assertThat(persisted.isValid()).isFalse();
        assertThat(persisted.getValidationWarnings()).contains("context");
    }

    @Test
    @DisplayName("PRIVATE dataset rename refreshes its suite's isValid before the PUT returns")
    void privateRenameRefreshesSuiteValidity() {
        final DatasetResponseDto publicDataset = createDataset(field("file_path"));
        final TestSuiteResponseDto suite = createSuite(publicDataset.getId(), "file_path");
        final DatasetResponseDto privateDataset = createPrivateDatasetFor(suite.getId(), field("file_path"));
        assertThat(getSuite(suite.getId()).isValid()).isTrue();

        final ResponseEntity<String> response = put(privateDataset, List.of(withId(privateDataset, 0, "file_path2")));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(getSuite(suite.getId()).isValid()).isFalse();
    }

    @Test
    @DisplayName("rename on a PUBLIC dataset bound to a suite returns 409 and changes nothing")
    void renameOnBoundPublicRejected() {
        final DatasetResponseDto dataset = createDataset(field("a"));
        final UUID tc = seed(dataset.getId(), "{\"a\":\"v\"}");
        createSuite(dataset.getId(), "a");

        final ResponseEntity<String> response = put(dataset, List.of(withId(dataset, 0, "renamed")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("DATASET_FIELD_RENAME_FORBIDDEN");
        final DatasetResponseDto after = getDataset(dataset.getId());
        assertThat(after.getVersion()).isEqualTo(dataset.getVersion());
        assertThat(after.getTestCaseSchema().getFirst().getName()).isEqualTo("a");
        assertThat(dataOf(dataset.getId(), tc)).isEqualTo(Map.of("a", "v"));
    }

    @Test
    @DisplayName("rename on an unbound PUBLIC dataset succeeds")
    void renameOnUnboundPublicSucceeds() {
        final DatasetResponseDto dataset = createDataset(field("a"));
        final UUID tc = seed(dataset.getId(), "{\"a\":\"v\"}");

        final ResponseEntity<String> response = put(dataset, List.of(withId(dataset, 0, "renamed")));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(dataOf(dataset.getId(), tc)).isEqualTo(Map.of("renamed", "v"));
    }

    @Test
    @DisplayName("update with an unknown field id returns 400")
    void unknownIdRejected() {
        final DatasetResponseDto dataset = createDataset(field("a"));
        final FieldDefinitionDto bogus = field("a").toBuilder().id("no-such-id").build();

        assertThat(put(dataset, List.of(bogus)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("update with a duplicate field id returns 400")
    void duplicateIdRejected() {
        final DatasetResponseDto dataset = createDataset(field("a"), field("b"));
        final FieldDefinitionDto first = withId(dataset, 0, "a");
        final FieldDefinitionDto second = withId(dataset, 0, "b");

        assertThat(put(dataset, List.of(first, second)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("update with duplicate field names (case-insensitive) returns 400")
    void duplicateNameRejected() {
        final DatasetResponseDto dataset = createDataset(field("a"));

        assertThat(put(dataset, List.of(field("x"), field("X"))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("create with an id on a schema field returns 400")
    void idOnCreateRejected() {
        final DatasetRequestDto request = DatasetRequestDto.builder()
                .name("ids-" + UUID.randomUUID())
                .visibility(DatasetVisibility.PUBLIC)
                .testCaseSchema(List.of(field("a").toBuilder().id("client-id").build()))
                .build();

        final ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/datasets"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("id-less client update keeps the stored ids and is not a schema change")
    void idLessUpdateKeepsIds() {
        final DatasetResponseDto dataset = createDataset(field("a"), field("b"));
        final List<String> idsBefore = dataset.getTestCaseSchema().stream()
                .map(FieldDefinitionDto::getId)
                .toList();

        final ResponseEntity<String> response = put(dataset, List.of(field("a"), field("b")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getDataset(dataset.getId()).getTestCaseSchema())
                .extracting(FieldDefinitionDto::getId)
                .containsExactlyElementsOf(idsBefore);
    }

    @Test
    @DisplayName("two schema PUTs carrying the same version: the first succeeds, the second gets 409 VERSION_CONFLICT")
    void secondPutWithSameVersionConflicts() {
        final DatasetResponseDto dataset = createDataset(field("a"));

        final ResponseEntity<String> first = put(dataset, List.of(withId(dataset, 0, "a1")));
        final ResponseEntity<String> second = put(dataset, List.of(withId(dataset, 0, "a2")));

        assertThat(first.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("VERSION_CONFLICT");
        assertThat(datasetRepository.findById(dataset.getId()).orElseThrow().getTestCaseSchema())
                .contains("a1")
                .doesNotContain("a2");
    }

    @Test
    @DisplayName("OpenAPI spec shows field ids on dataset examples and documents the 409 rename example on PUT")
    void openApiCarriesFieldIdExamples() {
        final ResponseEntity<String> apiDocs = restTemplate.getForEntity(baseUrl() + "/v3/api-docs", String.class);
        assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);
        final JsonNode put = objectMapper
                .readTree(apiDocs.getBody())
                .path("paths")
                .path("/api/v1/datasets/{id}")
                .path("put");

        final JsonNode requestExamples =
                put.path("requestBody").path("content").path("application/json").path("examples");
        // request examples are injected as raw JSON strings
        final JsonNode fullRequest =
                objectMapper.readTree(requestExamples.path("full").path("value").asString());
        assertThat(fullRequest.path("testCaseSchema").get(0).path("id").asString())
                .isNotBlank();
        final JsonNode response200 = put.path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("examples");
        assertThat(response200
                        .path("minimal")
                        .path("value")
                        .path("testCaseSchema")
                        .get(0)
                        .path("id")
                        .asString())
                .isNotBlank();
        final JsonNode conflict = put.path("responses")
                .path("409")
                .path("content")
                .path("application/json")
                .path("examples");
        assertThat(conflict.path("minimal").path("value").path("code").asString())
                .isEqualTo("DATASET_FIELD_RENAME_FORBIDDEN");
    }

    // ---------------------------------------------------------------- helpers

    private static FieldDefinitionDto field(String name) {
        return typed(name, SchemaFieldType.STRING);
    }

    private static FieldDefinitionDto typed(String name, SchemaFieldType type) {
        return FieldDefinitionDto.builder().name(name).type(type).build();
    }

    private static FieldDefinitionDto perTurn(String name) {
        return field(name).toBuilder().perTurn(true).build();
    }

    /** Copy of the dataset's {@code index}-th stored field with a new name (id retained). */
    private static FieldDefinitionDto withId(DatasetResponseDto dataset, int index, String newName) {
        return dataset.getTestCaseSchema().get(index).toBuilder().name(newName).build();
    }

    private DatasetResponseDto createDataset(FieldDefinitionDto... schema) {
        final DatasetRequestDto request = DatasetRequestDto.builder()
                .name("ids-" + UUID.randomUUID())
                .visibility(DatasetVisibility.PUBLIC)
                .testCaseSchema(List.of(schema))
                .build();
        final ResponseEntity<DatasetResponseDto> response =
                restTemplate.postForEntity(apiUrl("/datasets"), jsonEntity(request), DatasetResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getTestCaseSchema())
                .allSatisfy(f -> assertThat(f.getId()).isNotBlank());
        return response.getBody();
    }

    private DatasetResponseDto createPrivateDatasetFor(UUID suiteId, FieldDefinitionDto... schema) {
        final DatasetRequestDto request = DatasetRequestDto.builder()
                .name("ids-private-" + UUID.randomUUID())
                .visibility(DatasetVisibility.PRIVATE)
                .bindToSuiteId(suiteId)
                .testCaseSchema(List.of(schema))
                .build();
        final ResponseEntity<DatasetResponseDto> response =
                restTemplate.postForEntity(apiUrl("/datasets"), jsonEntity(request), DatasetResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TestSuiteResponseDto createSuite(UUID datasetId, String dataField) {
        final TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("ids-suite-" + UUID.randomUUID())
                .suiteType(SuiteType.DEPLOYMENT)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/upload")
                        .requestBodySchema(
                                MultipartFormDataRequestBodySchemaDto.builder().build())
                        .build())
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/upload")
                        .body(MultipartFormDataRequestBodyDto.builder()
                                .content(List.of(FormPartDto.builder()
                                        .name("attachment")
                                        .type(FormPartType.FILE)
                                        .value("${{contract_file}}")
                                        .build()))
                                .build())
                        .build())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("contract_file")
                        .dataField(dataField)
                        .build()))
                .datasetId(datasetId)
                .build();
        final ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TestSuiteResponseDto getSuite(UUID id) {
        return restTemplate
                .getForEntity(apiUrl("/test-suites/" + id), TestSuiteResponseDto.class)
                .getBody();
    }

    private DatasetResponseDto getDataset(UUID id) {
        return restTemplate
                .getForEntity(apiUrl("/datasets/" + id), DatasetResponseDto.class)
                .getBody();
    }

    private ResponseEntity<String> put(DatasetResponseDto dataset, List<FieldDefinitionDto> schema) {
        final DatasetRequestDto request = DatasetRequestDto.builder()
                .name(dataset.getName())
                .description(dataset.getDescription())
                .testCaseSchema(schema)
                .build();
        final HttpHeaders headers = new HttpHeaders();
        headers.setIfMatch("\"" + dataset.getVersion() + "\"");
        return restTemplate.exchange(
                apiUrl("/datasets/" + dataset.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(request, headers),
                String.class);
    }

    private UUID seed(UUID datasetId, String dataJson) {
        return metaTestDataHelper.seedTestCaseInDataset(datasetId, "tc-" + UUID.randomUUID(), dataJson);
    }

    private Map<String, Object> dataOf(UUID datasetId, UUID testCaseId) {
        return parse(testCaseRepository
                .findByIdAndDatasetId(testCaseId, datasetId)
                .orElseThrow()
                .getData());
    }

    private Map<String, Object> parse(String json) {
        return objectMapper.readValue(json, new TypeReference<>() {});
    }
}
