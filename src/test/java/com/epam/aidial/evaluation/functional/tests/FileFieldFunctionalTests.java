package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
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
import org.springframework.web.util.UriComponentsBuilder;

@DisplayName("FILE Field and CSV/ZIP Export/Import Functional Tests")
public abstract class FileFieldFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("ff-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    @Test
    @DisplayName("Should create test case with FILE field referencing uploaded DIAL file")
    void shouldCreateTestCaseWithFileField() {
        TestSuiteResponseDto suite = createSuiteWithFileSchema();

        FileMetadataDto file =
                uploadFileToSuite(suite.getId(), "document.txt", "Hello from file".getBytes(StandardCharsets.UTF_8));

        TestCaseRequestDto req = TestCaseRequestDto.builder()
                .testCaseName("TC with file")
                .data(Map.of("prompt", "Analyze this", "document", file.getPath()))
                .build();
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases"),
                jsonEntity(req),
                TestCaseResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isValid()).isTrue();
        assertThat(response.getBody().getData()).containsEntry("document", file.getPath());
    }

    @Test
    @DisplayName("Export suite without FILE fields returns CSV")
    void exportWithoutFileFieldsReturnsCsv() {
        TestSuiteResponseDto suite = createSuiteWithoutFileSchema();
        createTestCaseInSuite(suite.getId(), "TC1", Map.of("prompt", "hello"));

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.parseMediaType("text/csv; charset=UTF-8")));
        ResponseEntity<String> response = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/export.csv"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("text/csv");
        assertThat(response.getBody()).contains("testCaseName");
        assertThat(response.getBody()).contains("TC1");
    }

    @Test
    @DisplayName("Export suite with FILE fields returns ZIP containing CSV + files")
    void exportWithFileFieldsReturnsZip() throws IOException {
        TestSuiteResponseDto suite = createSuiteWithFileSchema();

        FileMetadataDto file =
                uploadFileToSuite(suite.getId(), "report.txt", "Report content here".getBytes(StandardCharsets.UTF_8));

        createTestCaseInSuite(suite.getId(), "TC-File", Map.of("prompt", "Analyze", "document", file.getPath()));

        ResponseEntity<byte[]> response = restTemplate.getForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/export.csv"),
                byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        String contentType = response.getHeaders().getContentType().toString();
        assertThat(contentType).containsAnyOf("application/zip", "application/octet-stream");

        Map<String, byte[]> zipEntries = readZipEntries(response.getBody());
        assertThat(zipEntries).containsKey("test-cases.csv");

        String csv = new String(zipEntries.get("test-cases.csv"), StandardCharsets.UTF_8);
        assertThat(csv).contains("testCaseName");
        assertThat(csv).contains("TC-File");
        assertThat(csv).contains("files/");
        assertThat(csv).contains("report.txt");

        boolean hasFileEntry =
                zipEntries.keySet().stream().anyMatch(name -> name.startsWith("files/") && name.endsWith("report.txt"));
        assertThat(hasFileEntry).isTrue();
    }

    @Test
    @DisplayName("Export ZIP with ARRAY values serialized as valid JSON in CSV")
    void exportZipWithArrayValuesAsJson() throws IOException {
        TestSuiteResponseDto suite = createSuiteWithFileAndArraySchema();

        FileMetadataDto file =
                uploadFileToSuite(suite.getId(), "data.txt", "File content".getBytes(StandardCharsets.UTF_8));

        createTestCaseInSuite(
                suite.getId(),
                "TC-Mixed",
                Map.of(
                        "document", file.getPath(),
                        "tags", List.of("a", "b")));

        ResponseEntity<byte[]> response = restTemplate.getForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/export.csv"),
                byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString())
                .containsAnyOf("application/zip", "application/octet-stream");

        Map<String, byte[]> zipEntries = readZipEntries(response.getBody());
        assertThat(zipEntries).containsKey("test-cases.csv");

        String csv = new String(zipEntries.get("test-cases.csv"), StandardCharsets.UTF_8);
        assertThat(csv).contains("TC-Mixed");
        // ARRAY value must be valid JSON, not Java toString.
        // In raw CSV text, the JSON ["a","b"] is quoted as "[""a"",""b""]"
        assertThat(csv).contains("[\"\"a\"\",\"\"b\"\"]");
        assertThat(csv).doesNotContain("[a, b]");
    }

    @Test
    @DisplayName("Export with materializeFiles=false returns CSV with raw DIAL paths")
    void exportWithMaterializeFilesFalseReturnsCsv() {
        TestSuiteResponseDto suite = createSuiteWithFileSchema();

        FileMetadataDto file =
                uploadFileToSuite(suite.getId(), "report.txt", "Report content here".getBytes(StandardCharsets.UTF_8));

        createTestCaseInSuite(suite.getId(), "TC-File", Map.of("prompt", "Analyze", "document", file.getPath()));

        ResponseEntity<String> response = restTemplate.getForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId())
                        + "/test-cases/export.csv?materializeFiles=false"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("text/csv");
        assertThat(response.getBody()).contains("testCaseName");
        assertThat(response.getBody()).contains(file.getPath());
    }

    @Test
    @DisplayName("Import ZIP with files creates test cases with DIAL file references")
    void importZipWithFilesCreatesTestCases() {
        TestSuiteResponseDto suite = createSuiteWithFileSchema();

        String csv = "testCaseName,prompt,document\nZipTC,hello,files/1/document/myfile.txt";
        byte[] zipBytes = createZip(Map.of(
                "test-cases.csv", csv.getBytes(StandardCharsets.UTF_8),
                "files/1/document/myfile.txt", "File from ZIP".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<CsvImportResultDto> response =
                importFile(suite.getId(), zipBytes, "data.zip", "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(1);

        List<TestCaseResponseDto> cases = listTestCases(suite.getId());
        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).getTestCaseName()).isEqualTo("ZipTC");
        Object docValue = cases.get(0).getData().get("document");
        assertThat(docValue).isNotNull();
        String docString = docValue.toString();
        // The document field should contain a dataset-scoped DIAL file reference in short format
        assertThat(docString).startsWith("@ef/");
        assertThat(docString).contains("datasets/");
        assertThat(docString).contains(suite.getDatasetId().toString());
    }

    @Test
    @DisplayName("Import ZIP with missing file sets FILE field to empty and generates warning")
    void importZipWithMissingFileSetsFieldToEmpty() {
        TestSuiteResponseDto suite = createSuiteWithFileSchema();

        String csv = "testCaseName,prompt,document\nMissingFileTC,hello,files/1/document/ghost.txt";
        byte[] zipBytes = createZip(Map.of("test-cases.csv", csv.getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<CsvImportResultDto> response =
                importFile(suite.getId(), zipBytes, "missing.zip", "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(1);
        assertThat(response.getBody().getWarnings()).isNotEmpty();
        assertThat(response.getBody().getWarnings().stream()
                        .anyMatch(w -> w.getMessage().contains("File missing from archive")))
                .isTrue();

        List<TestCaseResponseDto> cases = listTestCases(suite.getId());
        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).getTestCaseName()).isEqualTo("MissingFileTC");
        Object docValue = cases.get(0).getData().get("document");
        assertThat(docValue == null || docValue.toString().isBlank()).isTrue();
    }

    @Test
    @DisplayName("Import CSV for suite with FILE fields stores DIAL paths as raw strings")
    void importCsvForSuiteWithFileFieldsStoresPathsAsStrings() {
        TestSuiteResponseDto suite = createSuiteWithFileSchema();

        String dialRef = "@ef/suites/" + suite.getId() + "/data.csv";
        String csv = "testCaseName,prompt,document\nCsvFileTC,hello," + dialRef;

        ResponseEntity<CsvImportResultDto> response = importCsv(suite.getId(), csv, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(1);

        List<TestCaseResponseDto> cases = listTestCases(suite.getId());
        assertThat(cases).hasSize(1);
        Object docValue = cases.get(0).getData().get("document");
        assertThat(docValue).isNotNull();
        assertThat(docValue.toString()).isEqualTo(dialRef);
    }

    // --- Helpers ---

    private TestSuiteResponseDto createSuiteWithFileSchema() {
        return createSuite(
                "File Suite " + UUID.randomUUID(),
                List.of(
                        FieldDefinitionDto.builder()
                                .name("prompt")
                                .type(SchemaFieldType.STRING)
                                .required(true)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("document")
                                .type(SchemaFieldType.FILE)
                                .required(false)
                                .build()));
    }

    private TestSuiteResponseDto createSuiteWithFileAndArraySchema() {
        return createSuite(
                "File+Array Suite " + UUID.randomUUID(),
                List.of(
                        FieldDefinitionDto.builder()
                                .name("document")
                                .type(SchemaFieldType.FILE)
                                .required(false)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("tags")
                                .type(SchemaFieldType.ARRAY)
                                .required(false)
                                .build()));
    }

    private TestSuiteResponseDto createSuiteWithoutFileSchema() {
        return createSuite(
                "Plain Suite " + UUID.randomUUID(),
                List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build()));
    }

    private TestSuiteResponseDto createSuite(String name, List<FieldDefinitionDto> schema) {
        TestSuiteRequestDto req = TestSuiteRequestDto.builder()
                .name(name)
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

    private void createTestCaseInSuite(UUID suiteId, String name, Map<String, Object> data) {
        UUID datasetId = metaTestDataHelper.getDatasetId(suiteId);
        TestCaseRequestDto req =
                TestCaseRequestDto.builder().testCaseName(name).data(data).build();
        ResponseEntity<TestCaseResponseDto> r = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"), jsonEntity(req), TestCaseResponseDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private FileMetadataDto uploadFileToSuite(UUID suiteId, String filename, byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<FileMetadataDto> r = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suiteId + "/files"), new HttpEntity<>(body, headers), FileMetadataDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
    }

    private List<TestCaseResponseDto> listTestCases(UUID suiteId) {
        ResponseEntity<PageResponseDto<TestCaseResponseDto>> resp = restTemplate.exchange(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suiteId) + "/test-cases?includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<PageResponseDto<TestCaseResponseDto>>() {});
        return resp.getBody() != null ? resp.getBody().getContent() : List.of();
    }

    private ResponseEntity<CsvImportResultDto> importCsv(
            UUID suiteId, String csv, String importMode, String conflictStrategy) {
        URI uri = UriComponentsBuilder.fromUriString(
                        apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suiteId) + "/test-cases/import"))
                .queryParam("importMode", importMode)
                .queryParam("conflictStrategy", conflictStrategy)
                .build()
                .toUri();
        return restTemplate.postForEntity(
                uri, multipartEntity(csv.getBytes(StandardCharsets.UTF_8), "test.csv"), CsvImportResultDto.class);
    }

    private ResponseEntity<CsvImportResultDto> importFile(
            UUID suiteId, byte[] fileBytes, String filename, String importMode, String conflictStrategy) {
        URI uri = UriComponentsBuilder.fromUriString(
                        apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suiteId) + "/test-cases/import"))
                .queryParam("importMode", importMode)
                .queryParam("conflictStrategy", conflictStrategy)
                .build()
                .toUri();
        return restTemplate.postForEntity(uri, multipartEntity(fileBytes, filename), CsvImportResultDto.class);
    }

    private HttpEntity<MultiValueMap<String, Object>> multipartEntity(byte[] content, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return new HttpEntity<>(body, headers);
    }

    private byte[] createZip(Map<String, byte[]> entries) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create ZIP", e);
        }
        return baos.toByteArray();
    }

    private Map<String, byte[]> readZipEntries(byte[] zipBytes) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries.put(entry.getName(), zis.readAllBytes());
                zis.closeEntry();
            }
        }
        return entries;
    }
}
