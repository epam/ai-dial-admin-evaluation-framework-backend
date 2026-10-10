package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.runner.dto.RevalidationTaskDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.service.domain.dto.DatasetRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

@DisplayName("Dataset schema field ids — CSV import functional tests")
public abstract class DatasetSchemaFieldIdImportFunctionalTests extends BaseFunctionalTest {

    @Test
    @DisplayName("OVERRIDE keeps the ids of surviving columns and drops the rest")
    void overrideKeepsSurvivingIds() {
        final DatasetResponseDto dataset = createDataset(field("a"), field("b"));
        final String idOfA = idOf(dataset, "a");

        final ResponseEntity<String> response = importCsv(dataset.getId(), "testCaseName,a,c\nRow1,x,y", "OVERRIDE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        final List<FieldDefinitionDto> after = getDataset(dataset.getId()).getTestCaseSchema();
        assertThat(after).extracting(FieldDefinitionDto::getName).containsExactlyInAnyOrder("a", "c");
        assertThat(idOf(after, "a")).isEqualTo(idOfA);
        assertThat(idOf(after, "c")).isNotBlank().isNotEqualTo(idOf(dataset, "b"));
    }

    @Test
    @DisplayName("MERGE keeps existing ids and gives new columns fresh ids")
    void mergeKeepsExistingAndAddsNew() {
        final DatasetResponseDto dataset = createDataset(field("a"));

        final ResponseEntity<String> response = importCsv(dataset.getId(), "testCaseName,a,c\nRow1,x,y", "MERGE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        final List<FieldDefinitionDto> after = getDataset(dataset.getId()).getTestCaseSchema();
        assertThat(after).extracting(FieldDefinitionDto::getName).containsExactly("a", "c");
        assertThat(idOf(after, "a")).isEqualTo(idOf(dataset, "a"));
        assertThat(idOf(after, "c")).isNotBlank().isNotEqualTo(idOf(after, "a"));
    }

    @Test
    @DisplayName("OVERRIDE into a PUBLIC dataset bound to a suite, dropping a column, succeeds without 409")
    void overrideDroppingColumnOnBoundPublicSucceeds() {
        final DatasetResponseDto dataset = createDataset(field("a"), field("b"));
        createSuite(dataset.getId());

        final ResponseEntity<String> response = importCsv(dataset.getId(), "testCaseName,a\nRow1,x", "OVERRIDE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getDataset(dataset.getId()).getTestCaseSchema())
                .extracting(FieldDefinitionDto::getName)
                .containsExactly("a");
    }

    @Test
    @DisplayName("a schema-changing import records exactly one revalidation task")
    void schemaChangingImportRecordsOneTask() {
        final DatasetResponseDto dataset = createDataset(field("a"));
        final long tasksBefore = revalidationTaskCount(dataset.getId());

        final ResponseEntity<String> response = importCsv(dataset.getId(), "testCaseName,a,c\nRow1,x,y", "MERGE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revalidationTaskCount(dataset.getId())).isEqualTo(tasksBefore + 1);
    }

    @Test
    @DisplayName("an OVERRIDE that reproduces the current schema records no revalidation task and keeps the version")
    void unchangedOverrideRecordsNoTask() {
        final DatasetResponseDto dataset = createDataset(field("a"), field("b"));
        final long tasksBefore = revalidationTaskCount(dataset.getId());

        final ResponseEntity<String> response = importCsv(dataset.getId(), "testCaseName,a,b\nRow1,x,y", "OVERRIDE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revalidationTaskCount(dataset.getId())).isEqualTo(tasksBefore);
        assertThat(getDataset(dataset.getId()).getVersion()).isEqualTo(dataset.getVersion());
    }

    @Test
    @DisplayName("a PUT carrying the pre-import version after a schema-changing import returns 409 VERSION_CONFLICT")
    void putWithPreImportVersionConflicts() {
        final DatasetResponseDto dataset = createDataset(field("a"));
        assertThat(importCsv(dataset.getId(), "testCaseName,a,c\nRow1,x,y", "MERGE")
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        final ResponseEntity<String> response = put(dataset, dataset.getTestCaseSchema());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("VERSION_CONFLICT");
    }

    @Test
    @DisplayName("MERGE after a committed rename keeps the renamed field and its id")
    void mergeAfterRenameKeepsRenamedField() {
        final DatasetResponseDto dataset = createDataset(field("a"), field("b"));
        final String idOfB = idOf(dataset, "b");
        final FieldDefinitionDto renamed =
                dataset.getTestCaseSchema().get(1).toBuilder().name("b2").build();
        assertThat(put(dataset, List.of(dataset.getTestCaseSchema().get(0), renamed))
                        .getStatusCode()
                        .is2xxSuccessful())
                .isTrue();

        final ResponseEntity<String> response = importCsv(dataset.getId(), "testCaseName,a,c\nRow1,x,y", "MERGE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        final List<FieldDefinitionDto> after = getDataset(dataset.getId()).getTestCaseSchema();
        assertThat(after).extracting(FieldDefinitionDto::getName).containsExactly("a", "b2", "c");
        assertThat(idOf(after, "b2")).isEqualTo(idOfB);
    }

    // ---------------------------------------------------------------- helpers

    private static FieldDefinitionDto field(String name) {
        return FieldDefinitionDto.builder()
                .name(name)
                .type(SchemaFieldType.STRING)
                .build();
    }

    private static String idOf(DatasetResponseDto dataset, String name) {
        return idOf(dataset.getTestCaseSchema(), name);
    }

    private static String idOf(List<FieldDefinitionDto> schema, String name) {
        return schema.stream()
                .filter(f -> name.equals(f.getName()))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private DatasetResponseDto createDataset(FieldDefinitionDto... schema) {
        final DatasetRequestDto request = DatasetRequestDto.builder()
                .name("import-ids-" + UUID.randomUUID())
                .visibility(DatasetVisibility.PUBLIC)
                .testCaseSchema(List.of(schema))
                .build();
        final ResponseEntity<DatasetResponseDto> response =
                restTemplate.postForEntity(apiUrl("/datasets"), jsonEntity(request), DatasetResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private void createSuite(UUID datasetId) {
        final TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("import-ids-suite-" + UUID.randomUUID())
                .suiteType(SuiteType.DEPLOYMENT)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .build())
                .datasetId(datasetId)
                .build();
        final ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private DatasetResponseDto getDataset(UUID id) {
        return restTemplate
                .getForEntity(apiUrl("/datasets/" + id), DatasetResponseDto.class)
                .getBody();
    }

    private long revalidationTaskCount(UUID datasetId) {
        final ResponseEntity<PageResponseDto<RevalidationTaskDto>> response = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/revalidation-tasks?page=0&size=50"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody().getContent().size();
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

    private ResponseEntity<String> importCsv(UUID datasetId, String csv, String importMode) {
        final URI uri = UriComponentsBuilder.fromUriString(apiUrl("/datasets/" + datasetId + "/test-cases/import"))
                .queryParam("importMode", importMode)
                .queryParam("conflictStrategy", "FAIL")
                .build()
                .toUri();
        final MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "test.csv";
            }
        });
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.postForEntity(uri, new HttpEntity<>(body, headers), String.class);
    }
}
