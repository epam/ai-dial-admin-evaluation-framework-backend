package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

@DisplayName("Dataset File Management REST API Functional Tests")
public abstract class DatasetFileFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private DialFileStorageProperties fileStorageProperties;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    private UUID newDataset() {
        Dataset dataset = metaTestDataHelper.createDataset("dataset-file-" + UUID.randomUUID());
        return dataset.getId();
    }

    @Test
    @DisplayName("Should upload a file to a dataset and return 201 with dataset-shaped path")
    void shouldUploadFileToDataset() {
        UUID datasetId = newDataset();
        String content = "dataset payload";

        ResponseEntity<FileMetadataDto> response = uploadFileViaApi(datasetId, "data.csv", content);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        FileMetadataDto metadata = response.getBody();
        assertThat(metadata.getPath()).contains("/datasets/" + datasetId + "/data.csv");
        assertThat(metadata.getFilename()).isEqualTo("data.csv");
        assertThat(metadata.getSizeBytes()).isEqualTo(content.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("Should return 404 when uploading to non-existent dataset")
    void shouldReturn404ForNonExistentDataset() {
        UUID missing = UUID.randomUUID();

        ResponseEntity<String> response = uploadFileViaApiAsString(missing, "x.txt", "hi");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 400 when uploading file with invalid filename")
    void shouldReturn400ForInvalidFilename() {
        UUID datasetId = newDataset();

        ResponseEntity<String> response = uploadFileViaApiAsString(datasetId, "bad<name>.csv", "hi");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should return 400 when uploading duplicate filename")
    void shouldReturn400ForDuplicateFilename() {
        UUID datasetId = newDataset();
        uploadFileViaApi(datasetId, "dup.txt", "first");

        ResponseEntity<String> response = uploadFileViaApiAsString(datasetId, "dup.txt", "second");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("already exists");
    }

    @Test
    @DisplayName("Should return 400 when exceeding per-dataset file count limit")
    void shouldReturn400WhenExceedingPerDatasetLimit() {
        int originalLimit = fileStorageProperties.getMaxFilesPerDataset();
        fileStorageProperties.setMaxFilesPerDataset(2);
        try {
            UUID datasetId = newDataset();
            uploadFileViaApi(datasetId, "one.txt", "1");
            uploadFileViaApi(datasetId, "two.txt", "2");

            ResponseEntity<String> response = uploadFileViaApiAsString(datasetId, "three.txt", "3");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).contains("Maximum number of files");
        } finally {
            fileStorageProperties.setMaxFilesPerDataset(originalLimit);
        }
    }

    @Test
    @DisplayName("Should list files for a dataset")
    void shouldListFiles() {
        UUID datasetId = newDataset();
        uploadFileViaApi(datasetId, "a.txt", "aaa");
        uploadFileViaApi(datasetId, "b.txt", "bbb");

        ResponseEntity<List<FileMetadataDto>> response = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/files"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody())
                .extracting(FileMetadataDto::getFilename)
                .containsExactlyInAnyOrder("a.txt", "b.txt");
    }

    @Test
    @DisplayName("Should download a file with matching content")
    void shouldDownloadFile() {
        UUID datasetId = newDataset();
        String original = "download me";
        uploadFileViaApi(datasetId, "down.txt", original);

        ResponseEntity<byte[]> response =
                restTemplate.getForEntity(apiUrl("/datasets/" + datasetId + "/files/down.txt"), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8)).isEqualTo(original);
    }

    @Test
    @DisplayName("Should return 404 when downloading missing file")
    void shouldReturn404ForMissingDownload() {
        UUID datasetId = newDataset();

        ResponseEntity<String> response =
                restTemplate.getForEntity(apiUrl("/datasets/" + datasetId + "/files/missing.txt"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should delete a file and return 204; subsequent GET is 404")
    void shouldDeleteFile() {
        UUID datasetId = newDataset();
        uploadFileViaApi(datasetId, "del.txt", "bye");

        ResponseEntity<Void> deleteResp = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/files/del.txt"), HttpMethod.DELETE, null, Void.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> getResp =
                restTemplate.getForEntity(apiUrl("/datasets/" + datasetId + "/files/del.txt"), String.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should return 404 when deleting non-existent file")
    void shouldReturn404ForMissingDelete() {
        UUID datasetId = newDataset();

        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/files/nope.txt"), HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<FileMetadataDto> uploadFileViaApi(UUID datasetId, String filename, String content) {
        return restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/files"), buildMultipart(filename, content), FileMetadataDto.class);
    }

    private ResponseEntity<String> uploadFileViaApiAsString(UUID datasetId, String filename, String content) {
        return restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/files"), buildMultipart(filename, content), String.class);
    }

    private HttpEntity<MultiValueMap<String, Object>> buildMultipart(String filename, String content) {
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
        return new HttpEntity<>(body, headers);
    }
}
