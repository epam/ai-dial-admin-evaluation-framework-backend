package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
import com.epam.aidial.evaluation.service.domain.zip.ZipManifest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
import tools.jackson.core.JacksonException;

/**
 * End-to-end round trip and multi-request coverage for the {@code support-rich-zip-test-case-import}
 * change (tasks 6.1-6.3): a full export -&gt; import -&gt; export -&gt; import cycle preserving schema,
 * multi-turn shape and file content (design D2-D4, D6, D13; "Repeatable ZIP round trip" and "ZIP archives
 * carry test-case data only" in {@code test-case-zip-archive/spec.md}), and a ZIP-imported dataset run
 * under a multi-request suite.
 *
 * <p>Deliberately duplicates a handful of small HTTP helpers already present in {@link
 * ZipTestCaseImportFunctionalTests} and {@link FileFieldFunctionalTests} rather than extracting them,
 * per this task group's scope (new test class, no edits to those files beyond what they already contain).
 */
@DisplayName("ZIP Round Trip and Multi-Request Functional Tests")
public abstract class ZipRoundTripFunctionalTests extends AbstractMultiTurnFunctionalTest {

    // --- 6.1: double OVERRIDE round trip into the same dataset ---

    @Test
    @DisplayName("Double OVERRIDE round trip into the same dataset preserves schema, turns, file contents, "
            + "a public ref and the dataset's file count")
    void doubleOverrideRoundTrip_intoSameDataset_preservesEverything() {
        List<FieldDefinitionDto> schema = List.of(
                FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .perTurn(true)
                        .displayName("Prompt")
                        .description("The per-turn prompt text")
                        .build(),
                FieldDefinitionDto.builder()
                        .name("sharedDoc")
                        .type(SchemaFieldType.FILE)
                        .required(false)
                        .displayName("Shared Doc")
                        .description("A file shared across every turn")
                        .build(),
                FieldDefinitionDto.builder()
                        .name("turnDoc")
                        .type(SchemaFieldType.FILE)
                        .required(false)
                        .perTurn(true)
                        .displayName("Turn Doc")
                        .description("A file that differs per turn")
                        .build(),
                FieldDefinitionDto.builder()
                        .name("publicDoc")
                        .type(SchemaFieldType.FILE)
                        .required(false)
                        .displayName("Public Doc")
                        .description("A public reference, never uploaded")
                        .build());

        UUID datasetId = newDatasetWithSchema(schema);
        FileMetadataDto sharedFile =
                uploadDatasetFile(datasetId, "shared.bin", "SHARED_CONTENT".getBytes(StandardCharsets.UTF_8));
        FileMetadataDto turn0File =
                uploadDatasetFile(datasetId, "turn0.bin", "TURN0_CONTENT".getBytes(StandardCharsets.UTF_8));
        FileMetadataDto turn1File =
                uploadDatasetFile(datasetId, "turn1.bin", "TURN1_CONTENT".getBytes(StandardCharsets.UTF_8));
        String publicRef = "public/shared/guide.pdf";

        createMultiTurnCase(
                datasetId,
                "RoundTripCase",
                Map.of("sharedDoc", sharedFile.getPath(), "publicDoc", publicRef),
                List.of(
                        Map.of("prompt", "t0", "turnDoc", turn0File.getPath()),
                        Map.of("prompt", "t1", "turnDoc", turn1File.getPath())));

        List<FieldDefinitionDto> originalSchema = getDataset(datasetId).getTestCaseSchema();
        assertThat(listDatasetFiles(datasetId)).hasSize(3);

        for (int round = 1; round <= 2; round++) {
            byte[] zip = exportZip(datasetId);
            ResponseEntity<CsvImportResultDto> response = importZip(datasetId, zip, "OVERRIDE", "FAIL");
            assertThat(response.getStatusCode())
                    .as("round %d import status", round)
                    .isEqualTo(HttpStatus.OK);

            assertThat(getDataset(datasetId).getTestCaseSchema())
                    .as("round %d schema", round)
                    .containsExactlyInAnyOrderElementsOf(originalSchema);

            List<TestCaseResponseDto> cases = listTestCases(datasetId);
            assertThat(cases).as("round %d case count", round).hasSize(1);
            TestCaseResponseDto tc = cases.get(0);
            assertThat(tc.getTestCaseName()).isEqualTo("RoundTripCase");
            assertThat(tc.getData().get("sharedDoc")).isEqualTo(sharedFile.getPath());
            assertThat(tc.getData().get("publicDoc")).isEqualTo(publicRef);
            assertThat(tc.getMultiTurnData()).as("round %d turns", round).hasSize(2);
            assertThat(tc.getMultiTurnData().get(0).get("turnDoc")).isEqualTo(turn0File.getPath());
            assertThat(tc.getMultiTurnData().get(1).get("turnDoc")).isEqualTo(turn1File.getPath());
            assertThat(tc.getMultiTurnData().get(0).get("prompt")).isEqualTo("t0");
            assertThat(tc.getMultiTurnData().get(1).get("prompt")).isEqualTo("t1");

            assertThat(listDatasetFiles(datasetId))
                    .as("round %d file count", round)
                    .hasSize(3);
            assertThat(downloadDatasetFile(datasetId, "shared.bin"))
                    .isEqualTo("SHARED_CONTENT".getBytes(StandardCharsets.UTF_8));
            assertThat(downloadDatasetFile(datasetId, "turn0.bin"))
                    .isEqualTo("TURN0_CONTENT".getBytes(StandardCharsets.UTF_8));
            assertThat(downloadDatasetFile(datasetId, "turn1.bin"))
                    .isEqualTo("TURN1_CONTENT".getBytes(StandardCharsets.UTF_8));
        }
    }

    // --- 6.2: round trip into a different dataset; legacy no-manifest ZIP; legacy suite ref ---

    @Test
    @DisplayName("Round trip into a different, empty-schema dataset reproduces the source's schema and cases, "
            + "with EF-owned refs pointing at the destination's own files of equal content")
    void roundTrip_intoDifferentEmptySchemaDataset_reproducesSchemaAndFiles() {
        List<FieldDefinitionDto> schema = List.of(
                FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build(),
                FieldDefinitionDto.builder()
                        .name("document")
                        .type(SchemaFieldType.FILE)
                        .required(false)
                        .displayName("Document")
                        .description("An attached document")
                        .build());
        UUID sourceDatasetId = newDatasetWithSchema(schema);
        FileMetadataDto file =
                uploadDatasetFile(sourceDatasetId, "report.pdf", "REPORT_CONTENT".getBytes(StandardCharsets.UTF_8));
        createSingleTurnCase(sourceDatasetId, "SourceCase", Map.of("prompt", "hello", "document", file.getPath()));

        byte[] zip = exportZip(sourceDatasetId);

        UUID destinationDatasetId = newDatasetWithSchema(List.of());
        ResponseEntity<CsvImportResultDto> response = importZip(destinationDatasetId, zip, "OVERRIDE", "FAIL");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(getDataset(destinationDatasetId).getTestCaseSchema())
                .containsExactlyInAnyOrderElementsOf(getDataset(sourceDatasetId).getTestCaseSchema());

        List<TestCaseResponseDto> destCases = listTestCases(destinationDatasetId);
        assertThat(destCases).hasSize(1);
        TestCaseResponseDto destCase = destCases.get(0);
        assertThat(destCase.getTestCaseName()).isEqualTo("SourceCase");
        assertThat(destCase.getData().get("prompt")).isEqualTo("hello");
        String destDocRef = destCase.getData().get("document").toString();
        assertThat(destDocRef).isEqualTo("@ef/datasets/" + destinationDatasetId + "/report.pdf");
        assertThat(downloadDatasetFile(destinationDatasetId, "report.pdf"))
                .isEqualTo("REPORT_CONTENT".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("A legacy ZIP without a manifest, imported with OVERRIDE, keeps the FILE type for a column "
            + "whose cells hold files/... paths")
    void legacyZipWithoutManifest_overrideKeepsFileType() {
        UUID datasetId = newDatasetWithSchema(List.of());
        String csv = "testCaseName,document\nLegacyTC,files/1/report.pdf";
        byte[] zip = createZip(Map.of(
                "test-cases.csv", csv.getBytes(StandardCharsets.UTF_8),
                "files/1/report.pdf", "LEGACY_CONTENT".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<CsvImportResultDto> response = importZip(datasetId, zip, "OVERRIDE", "FAIL");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<FieldDefinitionDto> schema = getDataset(datasetId).getTestCaseSchema();
        assertThat(schema)
                .filteredOn(f -> "document".equals(f.getName()))
                .extracting(FieldDefinitionDto::getType)
                .containsExactly(SchemaFieldType.FILE);
        assertThat(downloadDatasetFile(datasetId, "report.pdf"))
                .isEqualTo("LEGACY_CONTENT".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("A legacy @ef/suites/... reference is copied into the dataset on the first round trip "
            + "(file count +1) and stable on the second")
    void legacySuiteRef_copiedOnFirstRoundTrip_stableOnSecond() {
        TestSuiteResponseDto foreignSuite = createChatSuite("Legacy Suite Ref Source");
        FileMetadataDto legacyFile =
                uploadFileToSuite(foreignSuite.getId(), "guide.pdf", "GUIDE_CONTENT".getBytes(StandardCharsets.UTF_8));
        assertThat(legacyFile.getPath()).contains("/suites/");

        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("document")
                .type(SchemaFieldType.FILE)
                .required(false)
                .build());
        UUID datasetId = newDatasetWithSchema(schema);
        createSingleTurnCase(datasetId, "LegacyRefCase", Map.of("document", legacyFile.getPath()));

        int fileCountBefore = listDatasetFiles(datasetId).size();

        byte[] zip1 = exportZip(datasetId);
        ResponseEntity<CsvImportResultDto> r1 = importZip(datasetId, zip1, "OVERRIDE", "FAIL");
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);

        String docRefAfterFirst =
                listTestCases(datasetId).get(0).getData().get("document").toString();
        assertThat(docRefAfterFirst).isEqualTo("@ef/datasets/" + datasetId + "/guide.pdf");
        assertThat(listDatasetFiles(datasetId)).hasSize(fileCountBefore + 1);
        assertThat(downloadDatasetFile(datasetId, "guide.pdf"))
                .isEqualTo("GUIDE_CONTENT".getBytes(StandardCharsets.UTF_8));

        byte[] zip2 = exportZip(datasetId);
        ResponseEntity<CsvImportResultDto> r2 = importZip(datasetId, zip2, "OVERRIDE", "FAIL");
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);

        String docRefAfterSecond =
                listTestCases(datasetId).get(0).getData().get("document").toString();
        assertThat(docRefAfterSecond).isEqualTo(docRefAfterFirst);
        assertThat(listDatasetFiles(datasetId)).hasSize(fileCountBefore + 1);
    }

    // --- 6.3: ZIP-imported case runs under a multi-request suite ---

    @Test
    @DisplayName("A ZIP-imported 2-turn case with a shared FILE field and a per-turn FILE field runs under a "
            + "multi-request suite, each request binding a different field, producing rows for every "
            + "(request_index, turn_index) pair with FILE values resolved")
    void zipImportedMultiTurnCase_runsUnderMultiRequestSuite_resolvesFileValuesPerRequest() {
        UUID datasetId = newDatasetWithSchema(List.of());
        ZipManifest manifest = new ZipManifest(
                1,
                List.of(
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
                                .build()),
                List.of());
        String csv = "testCaseName,turnIndex,sharedDoc,turnDoc\n"
                + "ZipRunCase,0,files/1/shared.bin,files/2/turn0.bin\n"
                + "ZipRunCase,1,files/1/shared.bin,files/3/turn1.bin";
        byte[] zip = createZip(Map.of(
                "test-cases.csv", csv.getBytes(StandardCharsets.UTF_8),
                "manifest.json", writeManifest(manifest),
                "files/1/shared.bin", "SHARED".getBytes(StandardCharsets.UTF_8),
                "files/2/turn0.bin", "TURN0".getBytes(StandardCharsets.UTF_8),
                "files/3/turn1.bin", "TURN1".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<CsvImportResultDto> importResponse = importZip(datasetId, zip, "OVERRIDE", "FAIL");
        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        String sharedRef = "@ef/datasets/" + datasetId + "/shared.bin";
        String turn0Ref = "@ef/datasets/" + datasetId + "/turn0.bin";
        String turn1Ref = "@ef/datasets/" + datasetId + "/turn1.bin";

        TestSuiteResponseDto suite = createMultiRequestSuiteOnDataset(datasetId);

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(chatReply("ok"));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(3);
        assertThat(results)
                .allSatisfy(r ->
                        assertThat(String.valueOf(r.get("execution_status"))).isEqualTo("SUCCESS"));
        assertThat(results)
                .allSatisfy(r -> assertThat(((Number) r.get("total_requests")).intValue())
                        .isEqualTo(2));

        Map<String, Object> request0Row = results.stream()
                .filter(r -> ((Number) r.get("request_index")).intValue() == 0)
                .findFirst()
                .orElseThrow();
        assertThat(((Number) request0Row.get("turn_index")).intValue()).isZero();
        assertThat(((Number) request0Row.get("total_turns")).intValue()).isEqualTo(1);
        assertThat(String.valueOf(request0Row.get("request_body"))).contains(sharedRef);

        List<Map<String, Object>> request1Rows = results.stream()
                .filter(r -> ((Number) r.get("request_index")).intValue() == 1)
                .toList();
        assertThat(request1Rows).hasSize(2);
        assertThat(request1Rows)
                .allSatisfy(r ->
                        assertThat(((Number) r.get("total_turns")).intValue()).isEqualTo(2));
        assertThat(request1Rows.stream().map(r -> ((Number) r.get("turn_index")).intValue()))
                .containsExactlyInAnyOrder(0, 1);

        Map<String, Object> turn0Row = request1Rows.stream()
                .filter(r -> ((Number) r.get("turn_index")).intValue() == 0)
                .findFirst()
                .orElseThrow();
        Map<String, Object> turn1Row = request1Rows.stream()
                .filter(r -> ((Number) r.get("turn_index")).intValue() == 1)
                .findFirst()
                .orElseThrow();
        assertThat(String.valueOf(turn0Row.get("request_body"))).contains(turn0Ref);
        assertThat(String.valueOf(turn1Row.get("request_body"))).contains(turn1Ref);
    }

    private TestSuiteResponseDto createMultiRequestSuiteOnDataset(UUID datasetId) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Zip Import Multi-Request " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/shared")
                        .build())
                .datasetId(datasetId)
                .requestName("useShared")
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/shared")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("op", "shared", "doc", "${{sharedDoc}}"))
                                .build())
                        .build())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("sharedDoc")
                        .dataField("sharedDoc")
                        .build()))
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("ack0")
                        .expression("choices[0].message.content")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .additionalRequests(List.of(RequestDefinitionDto.builder()
                        .name("useTurn")
                        .endpointRef(EndpointContractDto.builder()
                                .method(HttpMethod.POST)
                                .relativeUrlPattern("/v1/turn")
                                .build())
                        .requestTemplate(RequestTemplateDto.builder()
                                .urlTemplate("/v1/turn")
                                .body(JsonRequestBodyDto.builder()
                                        .content(Map.of("op", "turn", "doc", "${{turnDoc}}"))
                                        .build())
                                .build())
                        .inputBindings(List.of(InputBindingDto.builder()
                                .templateVariable("turnDoc")
                                .dataField("turnDoc")
                                .build()))
                        .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                                .name("ack1")
                                .expression("choices[0].message.content")
                                .type(SchemaFieldType.STRING)
                                .build()))
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    // --- shared helpers ---

    private byte[] writeManifest(ZipManifest manifest) {
        try {
            return objectMapper.writeValueAsBytes(manifest);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize manifest fixture", e);
        }
    }

    private DatasetResponseDto getDataset(UUID id) {
        return restTemplate
                .getForEntity(apiUrl("/datasets/" + id), DatasetResponseDto.class)
                .getBody();
    }

    private List<TestCaseResponseDto> listTestCases(UUID datasetId) {
        ResponseEntity<PageResponseDto<TestCaseResponseDto>> resp = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases?includeTotalCount=true"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<PageResponseDto<TestCaseResponseDto>>() {});
        return resp.getBody() != null ? resp.getBody().getContent() : List.of();
    }

    private List<FileMetadataDto> listDatasetFiles(UUID datasetId) {
        ResponseEntity<List<FileMetadataDto>> resp = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/files"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<FileMetadataDto>>() {});
        return resp.getBody() != null ? resp.getBody() : List.of();
    }

    private byte[] downloadDatasetFile(UUID datasetId, String filename) {
        ResponseEntity<byte[]> resp =
                restTemplate.getForEntity(apiUrl("/datasets/" + datasetId + "/files/" + filename), byte[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private FileMetadataDto uploadDatasetFile(UUID datasetId, String filename, byte[] content) {
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
                apiUrl("/datasets/" + datasetId + "/files"), new HttpEntity<>(body, headers), FileMetadataDto.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody();
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

    private byte[] exportZip(UUID datasetId) {
        ResponseEntity<byte[]> response =
                restTemplate.getForEntity(apiUrl("/datasets/" + datasetId + "/test-cases/export.csv"), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString())
                .containsAnyOf("application/zip", "application/octet-stream");
        return response.getBody();
    }

    private ResponseEntity<CsvImportResultDto> importZip(
            UUID datasetId, byte[] zip, String importMode, String conflictStrategy) {
        URI uri = UriComponentsBuilder.fromUriString(apiUrl("/datasets/" + datasetId + "/test-cases/import"))
                .queryParam("importMode", importMode)
                .queryParam("conflictStrategy", conflictStrategy)
                .build()
                .toUri();
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(zip) {
            @Override
            public String getFilename() {
                return "data.zip";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.postForEntity(uri, new HttpEntity<>(body, headers), CsvImportResultDto.class);
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
            throw new IllegalStateException("Failed to create ZIP fixture", e);
        }
        return baos.toByteArray();
    }
}
