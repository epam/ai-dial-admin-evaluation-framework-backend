package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.csv.CsvImportProperties;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.DatasetResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvConflictStrategy;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportMode;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.exception.VersionConflictException;
import com.epam.aidial.evaluation.service.domain.zip.ZipArchiveReader;
import com.epam.aidial.evaluation.service.domain.zip.ZipCsvFileRefRewriter;
import com.epam.aidial.evaluation.service.domain.zip.ZipFileImportPlanner;
import com.epam.aidial.evaluation.service.domain.zip.ZipImportColumnTypeResolver;
import com.epam.aidial.evaluation.service.domain.zip.ZipManifestSerializer;
import jakarta.validation.Validation;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;
import org.springframework.util.unit.DataUnit;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for {@link ZipImportService}'s own orchestration logic (design D6/D11): staging, journal
 * rollback, and the pre-write checks. Uses the real, already independently unit-tested pipeline components
 * ({@link ZipArchiveReader}, {@link ZipManifestSerializer}, {@link ZipCsvFileRefRewriter}, {@link
 * ZipImportColumnTypeResolver}, {@link ZipFileImportPlanner}) so this test only has to drive real ZIP bytes
 * through them, and mocks just the true external boundaries: {@link FileService} (DIAL writes), {@link
 * DatasetSchemaProvider}/{@link DatasetService} (DB reads) and {@link CsvImportService} (the DB write).
 */
@DisplayName("ZipImportService")
@ExtendWith(MockitoExtension.class)
class ZipImportServiceTest {

    private static final UUID DATASET_ID = UUID.randomUUID();

    @TempDir
    private Path tempDir;

    @Mock
    private FileService fileService;

    @Mock
    private DatasetSchemaProvider datasetSchemaProvider;

    @Mock
    private DatasetService datasetService;

    @Mock
    private CsvImportService csvImportService;

    @Mock
    private DialFileClient dialFileClient;

    private ZipImportService zipImportService;

    @BeforeEach
    void setUp() {
        CsvImportProperties csvImportProperties = csvImportProperties(DataSize.ofKilobytes(10));
        DialFileStorageProperties fileStorageProperties = fileStorageProperties();
        DialFileRefResolver dialFileRefResolver = new DialFileRefResolver(dialFileClient, fileStorageProperties);

        zipImportService = new ZipImportService(
                new ZipArchiveReader(csvImportProperties, fileStorageProperties),
                new ZipManifestSerializer(
                        new ObjectMapper(),
                        Validation.buildDefaultValidatorFactory().getValidator()),
                new ZipCsvFileRefRewriter(),
                new ZipImportColumnTypeResolver(),
                new ZipFileImportPlanner(fileService, dialFileRefResolver),
                datasetSchemaProvider,
                datasetService,
                fileService,
                csvImportService,
                csvImportProperties);

        when(datasetSchemaProvider.getSchema(DATASET_ID))
                .thenReturn(List.of(
                        FieldDefinitionDto.builder()
                                .name("prompt")
                                .type(SchemaFieldType.STRING)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("document")
                                .type(SchemaFieldType.FILE)
                                .build()));
        when(fileService.listByDataset(DATASET_ID)).thenReturn(List.of());
    }

    @Test
    @DisplayName("a successful import uploads the file, imports the CSV and leaves the caller's staged file alone")
    void importZip_happyPath_uploadsFileAndLeavesStagedFile() {
        Path staged = stageZip(Map.of(
                "test-cases.csv",
                "testCaseName,prompt,document\nTC,hi,files/1/a.txt".getBytes(StandardCharsets.UTF_8),
                "files/1/a.txt",
                "content".getBytes(StandardCharsets.UTF_8)));
        when(csvImportService.importCsv(any(), any(), anyLong(), anyChar(), any(), any(), any(), any()))
                .thenReturn(CsvImportResultDto.builder()
                        .totalRows(1)
                        .warnings(List.of())
                        .build());

        zipImportService.importZip(DATASET_ID, staged, ',', null, CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL);

        verify(fileService).putDatasetFile(eq(DATASET_ID), eq("a.txt"), any(), any());
        assertThat(Files.exists(staged)).isTrue();
    }

    @ParameterizedTest(name = "manifest contentType {0} → uploaded as {1}")
    @CsvSource(
            nullValues = "NULL",
            value = {"image/png, image/png", "'not a type', text/plain", "NULL, text/plain", "'  ', text/plain"})
    @DisplayName("an upload uses the manifest's valid content type, else guesses from the filename")
    void importZip_uploadContentType_prefersValidManifestValue(String manifestContentType, String expected) {
        String files = manifestContentType == null
                ? "[{\"path\":\"files/1/a.txt\",\"sourceRef\":\"@ef/datasets/x/a.txt\"}]"
                : "[{\"path\":\"files/1/a.txt\",\"sourceRef\":\"@ef/datasets/x/a.txt\",\"contentType\":\""
                        + manifestContentType + "\"}]";
        String manifest = "{\"formatVersion\":1,\"testCaseSchema\":[{\"name\":\"document\",\"type\":\"FILE\"}],"
                + "\"files\":" + files + "}";
        Path staged = stageZip(Map.of(
                "test-cases.csv",
                "testCaseName,document\nTC,files/1/a.txt".getBytes(StandardCharsets.UTF_8),
                "manifest.json",
                manifest.getBytes(StandardCharsets.UTF_8),
                "files/1/a.txt",
                "content".getBytes(StandardCharsets.UTF_8)));
        when(csvImportService.importCsv(any(), any(), anyLong(), anyChar(), any(), any(), any(), any()))
                .thenReturn(CsvImportResultDto.builder()
                        .totalRows(1)
                        .warnings(List.of())
                        .build());

        zipImportService.importZip(DATASET_ID, staged, ',', null, CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL);

        verify(fileService).putDatasetFile(eq(DATASET_ID), eq("a.txt"), any(), eq(expected));
    }

    @Test
    @DisplayName("on a RuntimeException from CsvImportService (409-style collision), the journal is rolled "
            + "back: the uploaded file is deleted, and the exception is rethrown")
    void importZip_csvImportThrows_rollsBackUploadedFile() {
        Path staged = stageZip(Map.of(
                "test-cases.csv",
                "testCaseName,prompt,document\nTC,hi,files/1/a.txt".getBytes(StandardCharsets.UTF_8),
                "files/1/a.txt",
                "content".getBytes(StandardCharsets.UTF_8)));
        RuntimeException collision = new RuntimeException("409-style collision");
        when(csvImportService.importCsv(any(), any(), anyLong(), anyChar(), any(), any(), any(), any()))
                .thenThrow(collision);

        assertThatThrownBy(() -> zipImportService.importZip(
                        DATASET_ID, staged, ',', null, CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL))
                .isSameAs(collision);

        verify(fileService).putDatasetFile(eq(DATASET_ID), eq("a.txt"), any(), any());
        verify(fileService).deleteByDataset(DATASET_ID, "a.txt");
    }

    @Test
    @DisplayName("when uploading a later file fails, the journal rolls back the earlier upload; CsvImportService "
            + "is never called")
    void importZip_uploadFails_rollsBackEarlierUploadAndNeverCallsCsvImportService() {
        Path staged = stageZip(Map.of(
                "test-cases.csv",
                        ("testCaseName,prompt,document\n" + "TC1,hi,files/1/a.txt\n" + "TC2,hi,files/2/b.txt")
                                .getBytes(StandardCharsets.UTF_8),
                "files/1/a.txt", "content-a".getBytes(StandardCharsets.UTF_8),
                "files/2/b.txt", "content-b".getBytes(StandardCharsets.UTF_8)));
        RuntimeException uploadFailure = new RuntimeException("DIAL upload failed");
        lenient().doThrow(uploadFailure).when(fileService).putDatasetFile(eq(DATASET_ID), eq("b.txt"), any(), any());

        assertThatThrownBy(() -> zipImportService.importZip(
                        DATASET_ID, staged, ',', null, CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL))
                .isSameAs(uploadFailure);

        verify(fileService).putDatasetFile(eq(DATASET_ID), eq("a.txt"), any(), any());
        verify(fileService).deleteByDataset(DATASET_ID, "a.txt");
        verify(fileService, never()).deleteByDataset(DATASET_ID, "b.txt");
        verifyNoInteractions(csvImportService);
    }

    @Test
    @DisplayName("a failed backup aborts before the overwrite: putDatasetFile is never called for that file")
    void importZip_backupFails_neverOverwritesTheFile() {
        when(fileService.listByDataset(DATASET_ID))
                .thenReturn(
                        List.of(FileMetadataDto.builder().filename("report.pdf").build()));
        doThrow(new RuntimeException("download failed"))
                .when(fileService)
                .downloadFromDataset(eq(DATASET_ID), eq("report.pdf"), any());
        Path staged = stageZip(Map.of(
                "test-cases.csv",
                "testCaseName,prompt,document\nTC,hi,files/1/report.pdf".getBytes(StandardCharsets.UTF_8),
                "files/1/report.pdf",
                "new content".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> zipImportService.importZip(
                        DATASET_ID, staged, ',', null, CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("download failed");

        verify(fileService, never()).putDatasetFile(eq(DATASET_ID), eq("report.pdf"), any(), any());
        verifyNoInteractions(csvImportService);
    }

    @Test
    @DisplayName("a stale If-Match version fails before any upload; CsvImportService is never called")
    void importZip_staleIfMatch_noWrites() {
        when(datasetService.getById(DATASET_ID))
                .thenReturn(DatasetResponseDto.builder().version(5L).build());
        Path staged = stageZip(Map.of(
                "test-cases.csv",
                "testCaseName,prompt,document\nTC,hi,files/1/a.txt".getBytes(StandardCharsets.UTF_8),
                "files/1/a.txt",
                "content".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> zipImportService.importZip(
                        DATASET_ID, staged, ',', 999L, CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL))
                .isInstanceOf(VersionConflictException.class);

        verify(fileService, never()).putDatasetFile(any(), any(), any(), any());
        verifyNoInteractions(csvImportService);
    }

    @Test
    @DisplayName("a rewritten CSV over csv.import.max-file-size fails before any upload, even though the "
            + "original (shorter, files/... path) CSV was within the limit")
    void importZip_rewrittenCsvOverSizeLimit_noWrites() {
        // The raw CSV (~50 bytes, "files/1/a.txt") passes ZipArchiveReader's own cap; the rewritten CSV
        // (~85 bytes, "@ef/datasets/<uuid>/a.txt") does not — so only *our* rewritten-CSV check (D6 step
        // 6), not ZipArchiveReader's, can be the one failing here.
        CsvImportProperties tightProperties = csvImportProperties(DataSize.ofBytes(60));
        ZipImportService tightService = new ZipImportService(
                new ZipArchiveReader(tightProperties, fileStorageProperties()),
                new ZipManifestSerializer(
                        new ObjectMapper(),
                        Validation.buildDefaultValidatorFactory().getValidator()),
                new ZipCsvFileRefRewriter(),
                new ZipImportColumnTypeResolver(),
                new ZipFileImportPlanner(fileService, new DialFileRefResolver(dialFileClient, fileStorageProperties())),
                datasetSchemaProvider,
                datasetService,
                fileService,
                csvImportService,
                tightProperties);
        Path staged = stageZip(Map.of(
                "test-cases.csv",
                "testCaseName,prompt,document\nTC,hi,files/1/a.txt".getBytes(StandardCharsets.UTF_8),
                "files/1/a.txt",
                "content".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> tightService.importZip(
                        DATASET_ID, staged, ',', null, CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Rewritten CSV size");

        verify(fileService, never()).putDatasetFile(any(), any(), any(), any());
        verifyNoInteractions(csvImportService);
    }

    private Path stageZip(Map<String, byte[]> entries) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ZipOutputStream zos = new ZipOutputStream(bos)) {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    zos.putNextEntry(new ZipEntry(entry.getKey()));
                    zos.write(entry.getValue());
                    zos.closeEntry();
                }
            }
            Path path = tempDir.resolve("staged-" + UUID.randomUUID() + ".zip");
            Files.write(path, bos.toByteArray());
            return path;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to stage ZIP fixture", e);
        }
    }

    private static CsvImportProperties csvImportProperties(DataSize maxFileSize) {
        CsvImportProperties properties = new CsvImportProperties();
        properties.setMaxFileSize(maxFileSize);
        properties.setMaxRows(10_000);
        properties.setBatchSize(100);
        CsvImportProperties.Zip zip = new CsvImportProperties.Zip();
        zip.setMaxEntries(1000);
        zip.setMaxTotalUncompressedSize(DataSize.of(10, DataUnit.MEGABYTES));
        properties.setZip(zip);
        return properties;
    }

    private static DialFileStorageProperties fileStorageProperties() {
        DialFileStorageProperties properties = new DialFileStorageProperties();
        properties.setBucketAlias("@ef");
        properties.setMaxFileSizeBytes(1_000_000);
        properties.setMaxFilesPerDataset(100);
        properties.setMaxFilesPerSuite(100);
        return properties;
    }
}
