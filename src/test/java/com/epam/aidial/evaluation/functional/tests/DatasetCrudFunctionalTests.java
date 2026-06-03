package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.DatasetRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.RevalidationTaskDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import java.util.List;
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

@DisplayName("Dataset CRUD Functional Tests")
public abstract class DatasetCrudFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    // -----------------------------------------------------------------------
    // create
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /datasets returns 201 with persisted dataset and ETag header")
    void createReturns201WithDataset() {
        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("Crud-Create-" + UUID.randomUUID())
                .description("smoke")
                .visibility(DatasetVisibility.PUBLIC)
                .testCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("query")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build()))
                .build();

        ResponseEntity<DatasetResponseDto> response =
                restTemplate.postForEntity(apiUrl("/datasets"), jsonEntity(request), DatasetResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isNotNull();
        assertThat(response.getBody().getName()).isEqualTo(request.getName());
        assertThat(response.getBody().getTestCaseSchema()).hasSize(1);
        assertThat(response.getBody().getTestCaseSchema().get(0).getName()).isEqualTo("query");
        assertThat(response.getBody().isValid()).isTrue();
        assertThat(response.getHeaders().getETag()).isNotBlank();
    }

    @Test
    @DisplayName("POST /datasets returns 409 when a dataset with the same name already exists")
    void createReturns409OnDuplicateName() {
        String name = "Dup-Name-" + UUID.randomUUID();
        DatasetRequestDto request = DatasetRequestDto.builder()
                .name(name)
                .visibility(DatasetVisibility.PUBLIC)
                .build();
        restTemplate.postForEntity(apiUrl("/datasets"), jsonEntity(request), DatasetResponseDto.class);

        ResponseEntity<String> dup = restTemplate.postForEntity(apiUrl("/datasets"), jsonEntity(request), String.class);

        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("POST /datasets returns 400 when name is blank")
    void createReturns400OnBlankName() {
        DatasetRequestDto request = DatasetRequestDto.builder()
                .name("")
                .visibility(DatasetVisibility.PUBLIC)
                .build();

        ResponseEntity<String> response =
                restTemplate.postForEntity(apiUrl("/datasets"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // -----------------------------------------------------------------------
    // getById / list
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("GET /datasets/{id} returns the dataset and 404 for unknown id")
    void getByIdReturnsOkAnd404() {
        Dataset seed = metaTestDataHelper.createDataset("Get-By-Id-" + UUID.randomUUID());

        ResponseEntity<DatasetResponseDto> ok =
                restTemplate.getForEntity(apiUrl("/datasets/" + seed.getId()), DatasetResponseDto.class);
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getBody()).isNotNull();
        assertThat(ok.getBody().getId()).isEqualTo(seed.getId());
        assertThat(ok.getHeaders().getETag()).isNotBlank();

        ResponseEntity<String> missing =
                restTemplate.getForEntity(apiUrl("/datasets/" + UUID.randomUUID()), String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /datasets lists datasets with pagination")
    void listReturnsPagedResults() {
        metaTestDataHelper.createDataset("List-A-" + UUID.randomUUID());
        metaTestDataHelper.createDataset("List-B-" + UUID.randomUUID());

        ResponseEntity<PageResponseDto<DatasetResponseDto>> response = restTemplate.exchange(
                apiUrl("/datasets?page=0&size=10&includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).isNotEmpty();
        assertThat(response.getBody().getTotalElements()).isGreaterThanOrEqualTo(2L);
    }

    // -----------------------------------------------------------------------
    // update — metadata-only (200) and schema-change (202)
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("PUT /datasets/{id} with metadata-only change returns 200 with updated dataset")
    void updateMetadataOnlyReturns200() {
        // Seed via the same POST path the production app uses so the stored schema JSON byte-shape
        // matches what the JsonbMapper produces on subsequent PUTs; otherwise the semantic JSON
        // equality check inside DatasetService.isSchemaChanged sees the bytes differ and routes
        // the request as a schema-change (202) instead of a metadata-only (200).
        String name = "Update-Meta-" + UUID.randomUUID();
        DatasetRequestDto seedRequest = DatasetRequestDto.builder()
                .name(name)
                .visibility(DatasetVisibility.PUBLIC)
                .testCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("q")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build()))
                .build();
        ResponseEntity<DatasetResponseDto> created =
                restTemplate.postForEntity(apiUrl("/datasets"), jsonEntity(seedRequest), DatasetResponseDto.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        DatasetResponseDto seed = created.getBody();
        assertThat(seed).isNotNull();

        DatasetRequestDto request = DatasetRequestDto.builder()
                .name(name + "-renamed")
                .description("updated description")
                .testCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("q")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build()))
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setIfMatch("\"" + seed.getVersion() + "\"");

        ResponseEntity<DatasetResponseDto> response = restTemplate.exchange(
                apiUrl("/datasets/" + seed.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(request, headers),
                DatasetResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getName()).isEqualTo(name + "-renamed");
        assertThat(response.getBody().getDescription()).isEqualTo("updated description");
    }

    @Test
    @DisplayName("PUT /datasets/{id} with testCaseSchema change returns 202 with revalidation task")
    void updateSchemaReturns202WithTask() {
        Dataset seed = metaTestDataHelper.createDataset("Update-Schema-" + UUID.randomUUID(), "[]");

        DatasetRequestDto request = DatasetRequestDto.builder()
                .name(seed.getName())
                .testCaseSchema(List.of(FieldDefinitionDto.builder()
                        .name("new_field")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setIfMatch("\"" + (seed.getVersion() == null ? 0L : seed.getVersion()) + "\"");

        ResponseEntity<RevalidationTaskDto> response = restTemplate.exchange(
                apiUrl("/datasets/" + seed.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(request, headers),
                RevalidationTaskDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTaskId()).isNotNull();
        assertThat(response.getBody().getDatasetId()).isEqualTo(seed.getId());
    }

    @Test
    @DisplayName("PUT /datasets/{id} returns 409 on If-Match version conflict")
    void updateReturns409OnStaleEtag() {
        Dataset seed = metaTestDataHelper.createDataset("Stale-Etag-" + UUID.randomUUID());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setIfMatch("\"99999\"");

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + seed.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(
                        DatasetRequestDto.builder().name(seed.getName()).build(), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // -----------------------------------------------------------------------
    // delete — 204 success and 409 RESTRICT
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("DELETE /datasets/{id} returns 204 when no suite references the dataset")
    void deleteReturns204OnSuccess() {
        Dataset seed = metaTestDataHelper.createDataset("Delete-Ok-" + UUID.randomUUID());

        ResponseEntity<Void> response =
                restTemplate.exchange(apiUrl("/datasets/" + seed.getId()), HttpMethod.DELETE, null, Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> followUp = restTemplate.getForEntity(apiUrl("/datasets/" + seed.getId()), String.class);
        assertThat(followUp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("DELETE /datasets/{id} returns 409 when a suite still references the dataset (FK RESTRICT)")
    void deleteReturns409WhenSuiteReferencesDataset() {
        Dataset seed = metaTestDataHelper.createDataset("Delete-Restrict-" + UUID.randomUUID());
        TestSuite suite = metaTestDataHelper.createTestSuite("Suite-Ref-" + UUID.randomUUID(), seed.getId());
        assertThat(suite.getDatasetId()).isEqualTo(seed.getId());

        ResponseEntity<String> response =
                restTemplate.exchange(apiUrl("/datasets/" + seed.getId()), HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains(suite.getName());
    }

    @Test
    @DisplayName("DELETE /datasets/{id} returns 404 for an unknown id")
    void deleteReturns404OnUnknownId() {
        ResponseEntity<String> response =
                restTemplate.exchange(apiUrl("/datasets/" + UUID.randomUUID()), HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
