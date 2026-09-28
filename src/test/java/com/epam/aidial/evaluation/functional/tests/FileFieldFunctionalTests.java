package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

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
        } catch (JacksonException e) {
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
    @DisplayName("Export suite with FILE fields returns ZIP containing CSV + manifest + files")
    void exportWithFileFieldsReturnsZip() throws IOException {
        TestSuiteResponseDto suite = createSuiteWithFileSchema();

        // uploadFileToSuite produces a legacy @ef/suites/... reference; export must still materialize it.
        FileMetadataDto file =
                uploadFileToSuite(suite.getId(), "report.txt", "Report content here".getBytes(StandardCharsets.UTF_8));
        assertThat(file.getPath()).contains("/suites/");

        createTestCaseInSuite(suite.getId(), "TC-File", Map.of("prompt", "Analyze", "document", file.getPath()));

        ResponseEntity<byte[]> response = restTemplate.getForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/export.csv"),
                byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        String contentType = response.getHeaders().getContentType().toString();
        assertThat(contentType).containsAnyOf("application/zip", "application/octet-stream");

        Map<String, byte[]> zipEntries = readZipEntries(response.getBody());
        assertThat(zipEntries).containsKeys("test-cases.csv", "manifest.json");

        String csv = new String(zipEntries.get("test-cases.csv"), StandardCharsets.UTF_8);
        assertThat(csv).contains("testCaseName,turnIndex,prompt,document");
        assertThat(csv).contains("TC-File");
        // the single distinct EF-owned reference gets number 1, first-seen order
        assertThat(csv).contains("files/1/report.txt");

        assertThat(zipEntries).containsKey("files/1/report.txt");
        assertThat(new String(zipEntries.get("files/1/report.txt"), StandardCharsets.UTF_8))
                .isEqualTo("Report content here");
    }

    @Test
    @DisplayName(
            "Export ZIP: multi-turn case writes one row per turn; shared FILE keeps one path, per-turn FILE differs")
    void exportZipMultiTurnCase_writesOneRowPerTurnWithSharedAndPerTurnFiles() throws IOException {
        TestSuiteResponseDto suite = createSuiteWithMultiTurnFileSchema();
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());

        FileMetadataDto sharedFile =
                uploadFileToSuite(suite.getId(), "shared.txt", "Shared content".getBytes(StandardCharsets.UTF_8));
        FileMetadataDto turn0File =
                uploadFileToSuite(suite.getId(), "turn0.txt", "Turn 0 content".getBytes(StandardCharsets.UTF_8));
        FileMetadataDto turn1File =
                uploadFileToSuite(suite.getId(), "turn1.txt", "Turn 1 content".getBytes(StandardCharsets.UTF_8));

        TestCaseRequestDto req = TestCaseRequestDto.builder()
                .testCaseName("MultiTurnTC")
                .data(Map.of("sharedDoc", sharedFile.getPath()))
                .multiTurnData(List.of(
                        Map.of("prompt", "t0", "turnDoc", turn0File.getPath()),
                        Map.of("prompt", "t1", "turnDoc", turn1File.getPath())))
                .build();
        ResponseEntity<TestCaseResponseDto> createResponse = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"), jsonEntity(req), TestCaseResponseDto.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<byte[]> response =
                restTemplate.getForEntity(apiUrl("/datasets/" + datasetId + "/test-cases/export.csv"), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, byte[]> zipEntries = readZipEntries(response.getBody());

        String csv = new String(zipEntries.get("test-cases.csv"), StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertThat(lines).hasSize(3); // header + 2 turn rows
        assertThat(lines[0]).isEqualTo("testCaseName,turnIndex,prompt,sharedDoc,turnDoc");
        assertThat(lines[1]).isEqualTo("MultiTurnTC,0,t0,files/1/shared.txt,files/2/turn0.txt");
        assertThat(lines[2]).isEqualTo("MultiTurnTC,1,t1,files/1/shared.txt,files/3/turn1.txt");

        assertThat(zipEntries).containsKeys("files/1/shared.txt", "files/2/turn0.txt", "files/3/turn1.txt");
        assertThat(new String(zipEntries.get("files/1/shared.txt"), StandardCharsets.UTF_8))
                .isEqualTo("Shared content");
        assertThat(new String(zipEntries.get("files/2/turn0.txt"), StandardCharsets.UTF_8))
                .isEqualTo("Turn 0 content");
        assertThat(new String(zipEntries.get("files/3/turn1.txt"), StandardCharsets.UTF_8))
                .isEqualTo("Turn 1 content");
        assertThat(zipEntries).containsKey("manifest.json");
    }

    @Test
    @DisplayName("Export ZIP: one file referenced by many test cases is written to the archive once")
    void exportZipFileUsedByManyCases_writesEntryOnce() throws IOException {
        TestSuiteResponseDto suite = createSuiteWithFileSchema();
        FileMetadataDto file =
                uploadFileToSuite(suite.getId(), "policy.pdf", "Policy content".getBytes(StandardCharsets.UTF_8));

        createTestCaseInSuite(suite.getId(), "TC-A", Map.of("prompt", "a", "document", file.getPath()));
        createTestCaseInSuite(suite.getId(), "TC-B", Map.of("prompt", "b", "document", file.getPath()));
        createTestCaseInSuite(suite.getId(), "TC-C", Map.of("prompt", "c", "document", file.getPath()));

        ResponseEntity<byte[]> response = restTemplate.getForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suite.getId()) + "/test-cases/export.csv"),
                byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, byte[]> zipEntries = readZipEntries(response.getBody());

        long fileEntryCount = zipEntries.keySet().stream()
                .filter(name -> name.startsWith("files/"))
                .count();
        assertThat(fileEntryCount).isEqualTo(1);
        assertThat(zipEntries).containsKey("files/1/policy.pdf");

        String csv = new String(zipEntries.get("test-cases.csv"), StandardCharsets.UTF_8);
        long referenceCount = csv.split("files/1/policy.pdf", -1).length - 1;
        assertThat(referenceCount).isEqualTo(3);
    }

    @Test
    @DisplayName("Export ZIP: a public/... reference is kept verbatim with no archive entry")
    void exportZipPublicReference_isVerbatimWithNoEntry() throws IOException {
        TestSuiteResponseDto suite = createSuiteWithFileSchema();
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createTestCaseInSuite(suite.getId(), "TC-Public", Map.of("prompt", "p", "document", "public/shared/guide.pdf"));

        ResponseEntity<byte[]> response =
                restTemplate.getForEntity(apiUrl("/datasets/" + datasetId + "/test-cases/export.csv"), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, byte[]> zipEntries = readZipEntries(response.getBody());

        String csv = new String(zipEntries.get("test-cases.csv"), StandardCharsets.UTF_8);
        assertThat(csv).contains("public/shared/guide.pdf");
        boolean hasFileEntry = zipEntries.keySet().stream().anyMatch(name -> name.startsWith("files/"));
        assertThat(hasFileEntry).isFalse();
        assertThat(zipEntries).containsKey("manifest.json");
    }

    @Test
    @DisplayName("Export ZIP: a file missing from DIAL storage returns an error status and no ZIP body")
    void exportZipMissingDialFile_returnsErrorStatusWithNoZip() {
        TestSuiteResponseDto suite = createSuiteWithFileSchema();
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        String missingRef = "@ef/suites/" + suite.getId() + "/ghost.pdf";
        createTestCaseInSuite(suite.getId(), "TC-Missing", Map.of("prompt", "p", "document", missingRef));

        // The endpoint's `produces` is restricted to text/csv and application/zip; accept anything so the
        // request reaches the handler (and its error response) instead of failing content negotiation.
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.ALL));
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases/export.csv"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        if (response.getHeaders().getContentType() != null) {
            assertThat(response.getHeaders().getContentType().toString()).doesNotContain("application/zip");
        }
        assertThat(response.getBody()).isNotNull();
        assertThat((String) response.getBody().get("message")).contains(missingRef);
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
        assertThat(zipEntries).containsKeys("test-cases.csv", "manifest.json");

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

    private TestSuiteResponseDto createSuiteWithMultiTurnFileSchema() {
        return createSuite(
                "MultiTurn File Suite " + UUID.randomUUID(),
                List.of(
                        FieldDefinitionDto.builder()
                                .name("prompt")
                                .type(SchemaFieldType.STRING)
                                .perTurn(true)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("sharedDoc")
                                .type(SchemaFieldType.FILE)
                                .required(false)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("turnDoc")
                                .type(SchemaFieldType.FILE)
                                .required(false)
                                .perTurn(true)
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
