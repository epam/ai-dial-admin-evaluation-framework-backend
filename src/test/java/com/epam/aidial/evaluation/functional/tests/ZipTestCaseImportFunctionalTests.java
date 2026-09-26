package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.properties.csv.CsvImportProperties;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportPreviewDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
import com.epam.aidial.evaluation.service.domain.zip.ZipManifest;
import com.epam.aidial.evaluation.service.domain.zip.ZipTestArchives;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
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
import org.springframework.util.unit.DataSize;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Functional tests for ZIP test-case import and preview orchestration (design D6/D7): {@code
 * ZipImportService}, wired end to end through {@code TestCaseController}'s {@code import}/{@code
 * import/preview} endpoints, real dataset file storage and a real Postgres-backed dataset/test-case
 * pipeline.
 */
@DisplayName("ZIP Test Case Import Functional Tests")
public abstract class ZipTestCaseImportFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DialFileStorageProperties fileStorageProperties;

    @Autowired
    private CsvImportProperties csvImportProperties;

    // --- 4.4: core rewrite/plan behaviour ---

    @Test
    @DisplayName("Semicolon delimiter and quoted values import correctly; files/... text inside a STRING cell "
            + "is left untouched")
    void semicolonDelimiterQuotedValuesAndFilesLikeText_importCorrectly() {
        UUID datasetId = newDataset(fileSchema());
        String csv = "testCaseName;prompt;document\n"
                + "\"Semi;TC\";\"Report says: files/1/report.pdf is attached; done\";files/1/report.pdf";
        byte[] zip = createZip(Map.of(
                "test-cases.csv", csv.getBytes(StandardCharsets.UTF_8),
                "files/1/report.pdf", "Report bytes".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<CsvImportResultDto> response = importZip(datasetId, zip, ";", "OVERRIDE", "FAIL", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalRows()).isEqualTo(1);

        List<TestCaseResponseDto> cases = listTestCases(datasetId);
        assertThat(cases).hasSize(1);
        assertThat(cases.get(0).getTestCaseName()).isEqualTo("Semi;TC");
        assertThat(cases.get(0).getData().get("prompt").toString())
                .isEqualTo("Report says: files/1/report.pdf is attached; done");
        assertThat(cases.get(0).getData().get("document").toString()).startsWith("@ef/datasets/" + datasetId + "/");
    }

    @Test
    @DisplayName("A missing referenced file stores a blank value and a warning naming the row and column")
    void missingReferencedFile_setsBlankAndWarnsWithRowAndColumn() {
        UUID datasetId = newDataset(fileSchema());
        String csv = "testCaseName,prompt,document\nGhostTC,hello,files/1/ghost.pdf";
        byte[] zip = createZip(Map.of("test-cases.csv", csv.getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<CsvImportResultDto> response = importZip(datasetId, zip, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getWarnings()).anySatisfy(w -> {
            assertThat(w.getMessage()).contains("File missing from archive: files/1/ghost.pdf");
            assertThat(w.getRowNumber()).isEqualTo(2);
            assertThat(w.getColumnName()).isEqualTo("document");
        });
        assertThat(listDatasetFiles(datasetId)).isEmpty();
    }

    @Test
    @DisplayName("APPEND into a non-empty schema does not upload files for a column the schema lacks")
    void appendMode_doesNotUploadFileForColumnSchemaLacks() {
        UUID datasetId = newDataset(List.of(FieldDefinitionDto.builder()
                .name("prompt")
                .type(SchemaFieldType.STRING)
                .required(true)
                .build()));
        String csv = "testCaseName,prompt,document\nAppendTC,hello,files/1/extra.pdf";
        byte[] zip = createZip(Map.of(
                "test-cases.csv", csv.getBytes(StandardCharsets.UTF_8),
                "files/1/extra.pdf", "extra bytes".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<CsvImportResultDto> response = importZip(datasetId, zip, "APPEND", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listDatasetFiles(datasetId)).isEmpty();
        assertThat(listTestCases(datasetId))
                .extracting(TestCaseResponseDto::getTestCaseName)
                .containsExactly("AppendTC");
    }

    @Test
    @DisplayName("OVERRIDE rewrites a column the dataset currently types STRING and re-types it FILE")
    void overrideMode_rewritesColumnDatasetCurrentlyTypesString() {
        UUID datasetId = newDataset(List.of(
                FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build(),
                FieldDefinitionDto.builder()
                        .name("document")
                        .type(SchemaFieldType.STRING)
                        .required(false)
                        .build()));
        String csv = "testCaseName,prompt,document\nOverrideTC,hello,files/1/photo.png";
        byte[] zip = createZip(Map.of(
                "test-cases.csv", csv.getBytes(StandardCharsets.UTF_8),
                "files/1/photo.png", "photo bytes".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<CsvImportResultDto> response = importZip(datasetId, zip, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listDatasetFiles(datasetId))
                .extracting(FileMetadataDto::getFilename)
                .containsExactly("photo.png");
        DatasetResponseDto dataset = getDataset(datasetId);
        assertThat(dataset.getTestCaseSchema().stream()
                        .filter(f -> "document".equals(f.getName()))
                        .findFirst()
                        .orElseThrow()
                        .getType())
                .isEqualTo(SchemaFieldType.FILE);
    }

    @Test
    @DisplayName("Legacy nested-folder layout: top folder stripped, __MACOSX ignored, unreferenced entries "
            + "not uploaded")
    void legacyNestedFolderLayout_ignoresMacosxAndUnreferencedEntries() {
        UUID datasetId = newDataset(fileSchema());
        String csv = "testCaseName,prompt,document\nNestedTC,hello,files/1/report.txt";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("archive/test-cases.csv", csv.getBytes(StandardCharsets.UTF_8));
        entries.put("archive/files/1/report.txt", "Nested content".getBytes(StandardCharsets.UTF_8));
        entries.put("archive/files/2/unused.txt", "Unused content".getBytes(StandardCharsets.UTF_8));
        entries.put("__MACOSX/archive/._test-cases.csv", new byte[] {1, 2, 3});
        byte[] zip = createZip(entries);

        ResponseEntity<CsvImportResultDto> response = importZip(datasetId, zip, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getTotalRows()).isEqualTo(1);
        assertThat(listDatasetFiles(datasetId))
                .extracting(FileMetadataDto::getFilename)
                .containsExactly("report.txt");
    }

    @Test
    @DisplayName("A public/... reference is kept verbatim with nothing written")
    void publicReference_keptVerbatimWithNothingWritten() {
        UUID datasetId = newDataset(fileSchema());
        String csv = "testCaseName,prompt,document\nPublicTC,hello,public/shared/guide.pdf";
        byte[] zip = createZip(Map.of("test-cases.csv", csv.getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<CsvImportResultDto> response = importZip(datasetId, zip, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listDatasetFiles(datasetId)).isEmpty();
        List<TestCaseResponseDto> cases = listTestCases(datasetId);
        assertThat(cases.get(0).getData().get("document")).isEqualTo("public/shared/guide.pdf");
    }

    @Test
    @DisplayName("Two different archive entries with the same name: one keeps it, the other gets a suffix")
    void duplicateEntryNames_getSuffix() {
        UUID datasetId = newDataset(List.of());
        String csv = "testCaseName,document\nDup1,files/1/report.pdf\nDup2,files/2/report.pdf";
        byte[] zip = createZip(Map.of(
                "test-cases.csv", csv.getBytes(StandardCharsets.UTF_8),
                "files/1/report.pdf", "V1".getBytes(StandardCharsets.UTF_8),
                "files/2/report.pdf", "V2".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<CsvImportResultDto> response = importZip(datasetId, zip, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listDatasetFiles(datasetId))
                .extracting(FileMetadataDto::getFilename)
                .containsExactlyInAnyOrder("report.pdf", "report_1.pdf");
        assertThat(downloadDatasetFile(datasetId, "report.pdf")).isEqualTo("V1".getBytes(StandardCharsets.UTF_8));
        assertThat(downloadDatasetFile(datasetId, "report_1.pdf")).isEqualTo("V2".getBytes(StandardCharsets.UTF_8));
    }

    // --- 4.5: writes and failures ---

    @Test
    @DisplayName("APPEND re-import overwrites a same-name file; an existing case sees the new content")
    void appendReimport_overwritesSameNameFile_existingCaseSeesNewContent() {
        UUID datasetId = newDataset(fileSchema());
        byte[] zip1 = createZip(Map.of(
                "test-cases.csv",
                "testCaseName,prompt,document\nCaseA,a,files/1/report.pdf".getBytes(StandardCharsets.UTF_8),
                "files/1/report.pdf",
                "V1".getBytes(StandardCharsets.UTF_8)));
        ResponseEntity<CsvImportResultDto> r1 = importZip(datasetId, zip1, "APPEND", "FAIL");
        assertThat(r1.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstCaseDoc =
                listTestCases(datasetId).get(0).getData().get("document").toString();

        byte[] zip2 = createZip(Map.of(
                "test-cases.csv",
                "testCaseName,prompt,document\nCaseB,b,files/1/report.pdf".getBytes(StandardCharsets.UTF_8),
                "files/1/report.pdf",
                "V2".getBytes(StandardCharsets.UTF_8)));
        ResponseEntity<CsvImportResultDto> r2 = importZip(datasetId, zip2, "APPEND", "FAIL");
        assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(listDatasetFiles(datasetId))
                .extracting(FileMetadataDto::getFilename)
                .containsExactly("report.pdf");
        assertThat(downloadDatasetFile(datasetId, "report.pdf")).isEqualTo("V2".getBytes(StandardCharsets.UTF_8));

        List<TestCaseResponseDto> cases = listTestCases(datasetId);
        assertThat(cases).extracting(TestCaseResponseDto::getTestCaseName).containsExactlyInAnyOrder("CaseA", "CaseB");
        String firstCaseDocAfter = cases.stream()
                .filter(c -> "CaseA".equals(c.getTestCaseName()))
                .findFirst()
                .orElseThrow()
                .getData()
                .get("document")
                .toString();
        assertThat(firstCaseDocAfter).isEqualTo(firstCaseDoc);
    }

    @Test
    @DisplayName("A manifest sourceRef pointing at the dataset's own file keeps its name; an archive-order-"
            + "first foreign file with the same name is suffixed instead")
    void manifestSourceRefPriority_keepsDatasetOwnFileName() throws JacksonException {
        UUID datasetId = newDataset(fileSchema());
        uploadDatasetFile(datasetId, "report.pdf", "ORIGINAL".getBytes(StandardCharsets.UTF_8));

        String foreignRef = "@ef/datasets/" + UUID.randomUUID() + "/report.pdf";
        String ownRef = "@ef/datasets/" + datasetId + "/report.pdf";
        ZipManifest manifest = new ZipManifest(
                1,
                fileSchema(),
                List.of(
                        new ZipManifest.FileEntry("files/1/report.pdf", foreignRef),
                        new ZipManifest.FileEntry("files/2/report.pdf", ownRef)));
        String csv = "testCaseName,prompt,document\n" + "Foreign,a,files/1/report.pdf\n" + "Mine,b,files/2/report.pdf";
        byte[] zip = createZip(Map.of(
                "test-cases.csv", csv.getBytes(StandardCharsets.UTF_8),
                "manifest.json", objectMapper.writeValueAsBytes(manifest),
                "files/1/report.pdf", "FOREIGN".getBytes(StandardCharsets.UTF_8),
                "files/2/report.pdf", "MINE".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<CsvImportResultDto> response = importZip(datasetId, zip, "APPEND", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloadDatasetFile(datasetId, "report.pdf")).isEqualTo("MINE".getBytes(StandardCharsets.UTF_8));
        assertThat(downloadDatasetFile(datasetId, "report_1.pdf"))
                .isEqualTo("FOREIGN".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("A suffix candidate skips an already-existing name; a cleaned-up (sanitized) name overwrites "
            + "the file it matches")
    void suffixSkipsExistingName_andSanitizedNameOverwrites() {
        UUID datasetId = newDataset(fileSchema());
        uploadDatasetFile(datasetId, "report.pdf", "PRE_EXISTING_0".getBytes(StandardCharsets.UTF_8));
        uploadDatasetFile(datasetId, "report_1.pdf", "PRE_EXISTING_1".getBytes(StandardCharsets.UTF_8));

        String csv = "testCaseName,document\n"
                + "GroupA1,files/1/report.pdf\n"
                + "GroupA2,files/2/report.pdf\n"
                + "Sanitized,files/3/report#1.pdf";
        byte[] zip = createZip(Map.of(
                "test-cases.csv", csv.getBytes(StandardCharsets.UTF_8),
                "files/1/report.pdf", "GROUP-A-1".getBytes(StandardCharsets.UTF_8),
                "files/2/report.pdf", "GROUP-A-2".getBytes(StandardCharsets.UTF_8),
                "files/3/report#1.pdf", "SANITIZED".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<CsvImportResultDto> response = importZip(datasetId, zip, "APPEND", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listDatasetFiles(datasetId))
                .extracting(FileMetadataDto::getFilename)
                .containsExactlyInAnyOrder("report.pdf", "report_1.pdf", "report_2.pdf");
        assertThat(downloadDatasetFile(datasetId, "report.pdf"))
                .isEqualTo("GROUP-A-1".getBytes(StandardCharsets.UTF_8));
        assertThat(downloadDatasetFile(datasetId, "report_2.pdf"))
                .isEqualTo("GROUP-A-2".getBytes(StandardCharsets.UTF_8));
        assertThat(downloadDatasetFile(datasetId, "report_1.pdf"))
                .isEqualTo("SANITIZED".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("A rewritten CSV over the size limit gives 400 with no files written")
    void rewrittenCsvOverSizeLimit_gives400NoWrites() {
        UUID datasetId = newDataset(fileSchema());
        DataSize original = csvImportProperties.getMaxFileSize();
        csvImportProperties.setMaxFileSize(DataSize.ofBytes(40));
        try {
            byte[] zip = createZip(Map.of(
                    "test-cases.csv",
                    "testCaseName,prompt,document\nTC,hello,files/1/report.pdf".getBytes(StandardCharsets.UTF_8),
                    "files/1/report.pdf",
                    "content".getBytes(StandardCharsets.UTF_8)));

            ResponseEntity<String> response = importZipRaw(datasetId, zip, "OVERRIDE", "FAIL");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(listDatasetFiles(datasetId)).isEmpty();
        } finally {
            csvImportProperties.setMaxFileSize(original);
        }
    }

    @Test
    @DisplayName("An entry whose header understates its real size gives 400 and rolls back earlier writes")
    void entryHeaderUnderstatesSize_gives400AndRollsBackEarlierWrites() throws IOException {
        UUID datasetId = newDataset(fileSchema());
        long originalCap = fileStorageProperties.getMaxFileSizeBytes();
        fileStorageProperties.setMaxFileSizeBytes(2000);
        try {
            byte[] realBigContent = new byte[5000];
            java.util.Arrays.fill(realBigContent, (byte) 'a');
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put(
                    "test-cases.csv",
                    ("testCaseName,prompt,document\n" + "Small,a,files/1/small.bin\n" + "Big,b,files/2/big.bin")
                            .getBytes(StandardCharsets.UTF_8));
            entries.put("files/1/small.bin", "hello".getBytes(StandardCharsets.UTF_8));
            entries.put("files/2/big.bin", realBigContent);
            byte[] zip = createZip(entries);
            byte[] lyingZip = ZipTestArchives.lieAboutUncompressedSize(zip, "files/2/big.bin", 10);

            ResponseEntity<String> response = importZipRaw(datasetId, lyingZip, "OVERRIDE", "FAIL");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(listDatasetFiles(datasetId)).isEmpty();
        } finally {
            fileStorageProperties.setMaxFileSizeBytes(originalCap);
        }
    }

    @Test
    @DisplayName("An archive over the entry-count limit gives 400 with no files written")
    void entryCountLimitExceeded_gives400() {
        UUID datasetId = newDataset(fileSchema());
        int originalMaxEntries = csvImportProperties.getZip().getMaxEntries();
        csvImportProperties.getZip().setMaxEntries(1);
        try {
            byte[] zip = createZip(Map.of(
                    "test-cases.csv",
                    "testCaseName,prompt,document\nTC,hello,files/1/x.pdf".getBytes(StandardCharsets.UTF_8),
                    "files/1/x.pdf",
                    "content".getBytes(StandardCharsets.UTF_8)));

            ResponseEntity<String> response = importZipRaw(datasetId, zip, "OVERRIDE", "FAIL");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(listDatasetFiles(datasetId)).isEmpty();
        } finally {
            csvImportProperties.getZip().setMaxEntries(originalMaxEntries);
        }
    }

    @Test
    @DisplayName("A path-traversal entry gives 400 with no files written")
    void pathTraversalEntry_gives400() {
        UUID datasetId = newDataset(fileSchema());
        byte[] zip = createZip(Map.of(
                "test-cases.csv",
                "testCaseName,prompt,document\nTC,hello,files/1/x.pdf".getBytes(StandardCharsets.UTF_8),
                "files/../evil.txt",
                "evil".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<String> response = importZipRaw(datasetId, zip, "OVERRIDE", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(listDatasetFiles(datasetId)).isEmpty();
    }

    @Test
    @DisplayName("Exceeding per-dataset file capacity gives 400 with no files written")
    void capacityLimitExceeded_gives400NoFilesWritten() {
        UUID datasetId = newDataset(fileSchema());
        uploadDatasetFile(datasetId, "existing.txt", "e".getBytes(StandardCharsets.UTF_8));
        int originalLimit = fileStorageProperties.getMaxFilesPerDataset();
        fileStorageProperties.setMaxFilesPerDataset(1);
        try {
            byte[] zip = createZip(Map.of(
                    "test-cases.csv",
                    "testCaseName,prompt,document\nTC,hello,files/1/new.txt".getBytes(StandardCharsets.UTF_8),
                    "files/1/new.txt",
                    "n".getBytes(StandardCharsets.UTF_8)));

            ResponseEntity<String> response = importZipRaw(datasetId, zip, "OVERRIDE", "FAIL");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(listDatasetFiles(datasetId))
                    .extracting(FileMetadataDto::getFilename)
                    .containsExactly("existing.txt");
        } finally {
            fileStorageProperties.setMaxFilesPerDataset(originalLimit);
        }
    }

    @Test
    @DisplayName("A referenced entry's declared size over the per-file cap gives 400 before any write")
    void referencedEntryOversizeDeclaredSize_gives400NoFilesWritten() {
        UUID datasetId = newDataset(fileSchema());
        long originalCap = fileStorageProperties.getMaxFileSizeBytes();
        fileStorageProperties.setMaxFileSizeBytes(5);
        try {
            byte[] zip = createZip(Map.of(
                    "test-cases.csv",
                    "testCaseName,prompt,document\nTC,hello,files/1/big.pdf".getBytes(StandardCharsets.UTF_8),
                    "files/1/big.pdf",
                    "much larger than the cap".getBytes(StandardCharsets.UTF_8)));

            ResponseEntity<String> response = importZipRaw(datasetId, zip, "OVERRIDE", "FAIL");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(listDatasetFiles(datasetId)).isEmpty();
        } finally {
            fileStorageProperties.setMaxFileSizeBytes(originalCap);
        }
    }

    @Test
    @DisplayName("conflictStrategy=FAIL 409 deletes created files and restores overwritten ones")
    void conflictStrategyFail409_deletesCreatedFilesAndRestoresOverwritten() {
        UUID datasetId = newDataset(fileSchema());
        uploadDatasetFile(datasetId, "existing.pdf", "OLD".getBytes(StandardCharsets.UTF_8));
        seedTestCase(datasetId, "Dup", Map.of("prompt", "seed"));

        String csv = "testCaseName,prompt,document\n"
                + "Dup,collide,files/1/existing.pdf\n"
                + "Brand2New,fresh,files/2/newone.pdf";
        byte[] zip = createZip(Map.of(
                "test-cases.csv", csv.getBytes(StandardCharsets.UTF_8),
                "files/1/existing.pdf", "NEW".getBytes(StandardCharsets.UTF_8),
                "files/2/newone.pdf", "CREATED".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<String> response = importZipRaw(datasetId, zip, "APPEND", "FAIL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(downloadDatasetFile(datasetId, "existing.pdf")).isEqualTo("OLD".getBytes(StandardCharsets.UTF_8));
        assertThat(listDatasetFiles(datasetId))
                .extracting(FileMetadataDto::getFilename)
                .containsExactly("existing.pdf");
        assertThat(listTestCases(datasetId))
                .extracting(TestCaseResponseDto::getTestCaseName)
                .containsExactly("Dup");
    }

    @Test
    @DisplayName("A stale If-Match gives 409 with no writes")
    void staleIfMatch_gives409NoWrites() {
        UUID datasetId = newDataset(fileSchema());
        long currentVersion = getDataset(datasetId).getVersion();
        byte[] zip = createZip(Map.of(
                "test-cases.csv",
                "testCaseName,prompt,document\nTC,hello,files/1/new.txt".getBytes(StandardCharsets.UTF_8),
                "files/1/new.txt",
                "n".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<String> response =
                importZipRaw(datasetId, zip, ",", "OVERRIDE", "FAIL", String.valueOf(currentVersion + 999));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(listDatasetFiles(datasetId)).isEmpty();
    }

    @Test
    @DisplayName("Preview shows the future ref with no write; import then produces the same ref and writes it")
    void previewImportParity_noWritesDuringPreview() {
        UUID datasetId = newDataset(fileSchema());
        byte[] zip = createZip(Map.of(
                "test-cases.csv",
                "testCaseName,prompt,document\nParityTC,hello,files/1/report.pdf".getBytes(StandardCharsets.UTF_8),
                "files/1/report.pdf",
                "PARITY".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<CsvImportPreviewDto> previewResponse = previewZip(datasetId, zip, "OVERRIDE", "FAIL");
        assertThat(previewResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listDatasetFiles(datasetId)).isEmpty();
        String previewRef = previewResponse
                .getBody()
                .getSampleRows()
                .get(0)
                .getData()
                .get("document")
                .toString();
        assertThat(previewRef).isEqualTo("@ef/datasets/" + datasetId + "/report.pdf");

        ResponseEntity<CsvImportResultDto> importResponse = importZip(datasetId, zip, "OVERRIDE", "FAIL");
        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listDatasetFiles(datasetId))
                .extracting(FileMetadataDto::getFilename)
                .containsExactly("report.pdf");
        String importedRef =
                listTestCases(datasetId).get(0).getData().get("document").toString();
        assertThat(importedRef).isEqualTo(previewRef);
    }

    // --- fixtures ---

    private List<FieldDefinitionDto> fileSchema() {
        return List.of(
                FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(false)
                        .build(),
                FieldDefinitionDto.builder()
                        .name("document")
                        .type(SchemaFieldType.FILE)
                        .required(false)
                        .build());
    }

    private UUID newDataset(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            return metaTestDataHelper
                    .createDataset("zip-import-" + UUID.randomUUID(), schemaJson)
                    .getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    private void seedTestCase(UUID datasetId, String name, Map<String, Object> data) {
        metaTestDataHelper.seedTestCaseInDataset(datasetId, name, toJson(data));
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
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

    private ResponseEntity<CsvImportResultDto> importZip(
            UUID datasetId, byte[] zip, String importMode, String conflictStrategy) {
        return importZip(datasetId, zip, ",", importMode, conflictStrategy, null);
    }

    private ResponseEntity<CsvImportResultDto> importZip(
            UUID datasetId, byte[] zip, String delimiter, String importMode, String conflictStrategy, String ifMatch) {
        return doImport(datasetId, zip, delimiter, importMode, conflictStrategy, ifMatch, CsvImportResultDto.class);
    }

    private ResponseEntity<String> importZipRaw(
            UUID datasetId, byte[] zip, String importMode, String conflictStrategy) {
        return importZipRaw(datasetId, zip, ",", importMode, conflictStrategy, null);
    }

    private ResponseEntity<String> importZipRaw(
            UUID datasetId, byte[] zip, String delimiter, String importMode, String conflictStrategy, String ifMatch) {
        return doImport(datasetId, zip, delimiter, importMode, conflictStrategy, ifMatch, String.class);
    }

    private <T> ResponseEntity<T> doImport(
            UUID datasetId,
            byte[] zip,
            String delimiter,
            String importMode,
            String conflictStrategy,
            String ifMatch,
            Class<T> responseType) {
        URI uri = UriComponentsBuilder.fromUriString(apiUrl("/datasets/" + datasetId + "/test-cases/import"))
                .queryParam("delimiter", delimiter)
                .queryParam("importMode", importMode)
                .queryParam("conflictStrategy", conflictStrategy)
                .build()
                .toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (ifMatch != null) {
            headers.set(HttpHeaders.IF_MATCH, ifMatch);
        }
        return restTemplate.postForEntity(uri, new HttpEntity<>(zipBody(zip), headers), responseType);
    }

    private ResponseEntity<CsvImportPreviewDto> previewZip(
            UUID datasetId, byte[] zip, String importMode, String conflictStrategy) {
        URI uri = UriComponentsBuilder.fromUriString(apiUrl("/datasets/" + datasetId + "/test-cases/import/preview"))
                .queryParam("importMode", importMode)
                .queryParam("conflictStrategy", conflictStrategy)
                .build()
                .toUri();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return restTemplate.postForEntity(uri, new HttpEntity<>(zipBody(zip), headers), CsvImportPreviewDto.class);
    }

    private MultiValueMap<String, Object> zipBody(byte[] zip) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(zip) {
            @Override
            public String getFilename() {
                return "data.zip";
            }
        });
        return body;
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
