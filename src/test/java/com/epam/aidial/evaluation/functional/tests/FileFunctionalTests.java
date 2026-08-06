package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteDeleteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("File Management REST API Functional Tests")
public abstract class FileFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private DialFileStorageProperties fileStorageProperties;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("file-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    @Test
    @DisplayName("Should upload a file and return 201 with FileMetadataDto")
    void shouldUploadFile() {
        TestSuiteResponseDto suite = createTestSuiteViaApi();
        String content = "sample file content";

        ResponseEntity<FileMetadataDto> response = uploadFileViaApi(suite.getId(), "document.txt", content);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        FileMetadataDto metadata = response.getBody();
        assertThat(metadata.getPath()).isNotNull();
        assertThat(metadata.getPath()).contains("document.txt");
        assertThat(metadata.getFilename()).isEqualTo("document.txt");
        assertThat(metadata.getContentType()).isEqualTo("text/plain");
        assertThat(metadata.getSizeBytes()).isEqualTo(content.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("Should list files for a test suite")
    void shouldListFiles() {
        TestSuiteResponseDto suite = createTestSuiteViaApi();
        uploadFileViaApi(suite.getId(), "file1.txt", "content one");
        uploadFileViaApi(suite.getId(), "file2.txt", "content two");

        ResponseEntity<List<FileMetadataDto>> response = restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId() + "/files"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody())
                .extracting(FileMetadataDto::getFilename)
                .containsExactlyInAnyOrder("file1.txt", "file2.txt");
    }

    @Test
    @DisplayName("Should download a file with matching content")
    void shouldDownloadFile() {
        TestSuiteResponseDto suite = createTestSuiteViaApi();
        String originalContent = "download me please";
        uploadFileViaApi(suite.getId(), "download.txt", originalContent);

        ResponseEntity<byte[]> response = restTemplate.getForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/files/download.txt"), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(originalContent);
        assertThat(response.getHeaders().getContentType()).isNotNull();
    }

    @Test
    @DisplayName("Should delete a file and return 204, file is no longer accessible")
    void shouldDeleteFile() {
        TestSuiteResponseDto suite = createTestSuiteViaApi();
        uploadFileViaApi(suite.getId(), "to-delete.txt", "delete me");

        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId() + "/files/to-delete.txt"), HttpMethod.DELETE, null, Void.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> getResponse = restTemplate.getForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/files/to-delete.txt"), String.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 404 when uploading to a non-existent test suite")
    void shouldReturn404WhenUploadingToNonExistentSuite() {
        UUID nonExistentSuiteId = UUID.randomUUID();

        ResponseEntity<String> response = uploadFileViaApiAsString(nonExistentSuiteId, "orphan.txt", "content");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 400 when uploading an empty file")
    void shouldReturn400WhenUploadingEmptyFile() {
        TestSuiteResponseDto suite = createTestSuiteViaApi();

        ResponseEntity<String> response = uploadFileViaApiAsString(suite.getId(), "empty.txt", "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("empty");
    }

    @Test
    @DisplayName("Should return 404 when downloading a non-existent file")
    void shouldReturn404WhenDownloadingNonExistentFile() {
        TestSuiteResponseDto suite = createTestSuiteViaApi();

        ResponseEntity<String> response = restTemplate.getForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/files/nonexistent.txt"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 404 when deleting a non-existent file")
    void shouldReturn404WhenDeletingNonExistentFile() {
        TestSuiteResponseDto suite = createTestSuiteViaApi();

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId() + "/files/nonexistent.txt"),
                HttpMethod.DELETE,
                null,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should cascade delete files when test suite is deleted")
    void shouldCascadeDeleteFilesWhenSuiteIsDeleted() {
        TestSuiteResponseDto suite = createTestSuiteViaApi();
        uploadFileViaApi(suite.getId(), "cascade1.txt", "file one");
        uploadFileViaApi(suite.getId(), "cascade2.txt", "file two");

        ResponseEntity<TestSuiteDeleteResponseDto> deleteResponse = restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId()),
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                TestSuiteDeleteResponseDto.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deleteResponse.getBody()).isNotNull();
        assertThat(deleteResponse.getBody().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("Should return 404 when listing files for a non-existent suite")
    void shouldReturn404WhenListingFilesForNonExistentSuite() {
        UUID nonExistentSuiteId = UUID.randomUUID();

        ResponseEntity<String> response =
                restTemplate.getForEntity(apiUrl("/test-suites/" + nonExistentSuiteId + "/files"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should upload multiple files and list them all")
    void shouldUploadMultipleFilesAndListAll() {
        TestSuiteResponseDto suite = createTestSuiteViaApi();

        uploadFileViaApi(suite.getId(), "alpha.txt", "aaa");
        uploadFileViaApi(suite.getId(), "beta.txt", "bbb");
        uploadFileViaApi(suite.getId(), "gamma.txt", "ccc");

        ResponseEntity<List<FileMetadataDto>> response = restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId() + "/files"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(3);
        assertThat(response.getBody())
                .extracting(FileMetadataDto::getFilename)
                .containsExactlyInAnyOrder("alpha.txt", "beta.txt", "gamma.txt");
    }

    @Test
    @DisplayName("Should return 400 when uploading duplicate filename")
    void shouldReturn400WhenUploadingDuplicateFilename() {
        TestSuiteResponseDto suite = createTestSuiteViaApi();
        uploadFileViaApi(suite.getId(), "duplicate.txt", "first");

        ResponseEntity<String> response = uploadFileViaApiAsString(suite.getId(), "duplicate.txt", "second");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("already exists");
    }

    @Test
    @DisplayName("Should return 400 when exceeding per-suite file count limit")
    void shouldReturn400WhenExceedingFileCountLimit() {
        int originalLimit = fileStorageProperties.getMaxFilesPerSuite();
        fileStorageProperties.setMaxFilesPerSuite(2);
        try {
            TestSuiteResponseDto suite = createTestSuiteViaApi();
            uploadFileViaApi(suite.getId(), "file1.txt", "one");
            uploadFileViaApi(suite.getId(), "file2.txt", "two");

            ResponseEntity<String> response = uploadFileViaApiAsString(suite.getId(), "file3.txt", "three");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).contains("Maximum number of files");
        } finally {
            fileStorageProperties.setMaxFilesPerSuite(originalLimit);
        }
    }

    private TestSuiteResponseDto createTestSuiteViaApi() {
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name("File Suite " + UUID.randomUUID())
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of()))
                .build();
        ResponseEntity<TestSuiteResponseDto> r =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(req), TestSuiteResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(r.getBody()).isNotNull();
        return r.getBody();
    }

    private ResponseEntity<FileMetadataDto> uploadFileViaApi(UUID suiteId, String filename, String content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.postForEntity(
                apiUrl("/test-suites/" + suiteId + "/files"), new HttpEntity<>(body, headers), FileMetadataDto.class);
    }

    private ResponseEntity<String> uploadFileViaApiAsString(UUID suiteId, String filename, String content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.postForEntity(
                apiUrl("/test-suites/" + suiteId + "/files"), new HttpEntity<>(body, headers), String.class);
    }
}
