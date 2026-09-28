package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.csv.CsvExportProperties;
import com.epam.aidial.evaluation.configuration.properties.pagination.PaginationProperties;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.csv.TestCaseExportRowProjector;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.zip.ZipExportFileCollector;
import com.epam.aidial.evaluation.service.domain.zip.ZipManifest;
import com.epam.aidial.evaluation.service.domain.zip.ZipManifestSerializer;
import jakarta.validation.Validation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ZipExportService — buildZip")
@ExtendWith(MockitoExtension.class)
class ZipExportServiceTest {

    private static final String BUCKET_ALIAS = "@ef";
    private static final String REAL_BUCKET = "real-bucket";

    @Mock
    private DatasetRepository datasetRepository;

    @Mock
    private DatasetSchemaProvider datasetSchemaProvider;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private DialFileClient dialFileClient;

    private ZipExportService service;
    private UUID datasetId;

    @BeforeEach
    void setUp() {
        datasetId = UUID.randomUUID();

        DialFileStorageProperties fileStorageProperties = new DialFileStorageProperties();
        fileStorageProperties.setBucketAlias(BUCKET_ALIAS);
        DialFileRefResolver dialFileRefResolver = new DialFileRefResolver(dialFileClient, fileStorageProperties);
        FileRefValidator fileRefValidator = new FileRefValidator(fileStorageProperties);
        ZipExportFileCollector zipExportFileCollector =
                new ZipExportFileCollector(dialFileRefResolver, fileRefValidator);

        ObjectMapper objectMapper = new ObjectMapper();
        ZipManifestSerializer zipManifestSerializer = new ZipManifestSerializer(
                objectMapper, Validation.buildDefaultValidatorFactory().getValidator());
        FilterParser filterParser = new FilterParser();

        CsvExportProperties csvExportProperties = new CsvExportProperties();
        csvExportProperties.setPageSize(100);
        PaginationProperties paginationProperties = new PaginationProperties();
        paginationProperties.setDefaultSize(20);
        paginationProperties.setMaxSize(100);

        TestCaseExportRowProjector rowProjector = new TestCaseExportRowProjector(objectMapper);

        service = new ZipExportService(
                datasetRepository,
                datasetSchemaProvider,
                testCaseRepository,
                dialFileClient,
                zipExportFileCollector,
                zipManifestSerializer,
                filterParser,
                objectMapper,
                csvExportProperties,
                paginationProperties,
                rowProjector);

        when(datasetRepository.existsById(datasetId)).thenReturn(true);
    }

    @Test
    @DisplayName("multi-turn case is written as one row per turn, with shared data merged into every row")
    void buildZip_multiTurnCase_writesOneRowPerTurn() throws IOException {
        List<FieldDefinitionDto> schema = List.of(
                FieldDefinitionDto.builder()
                        .name("shared")
                        .type(SchemaFieldType.STRING)
                        .build(),
                FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .perTurn(true)
                        .build());
        when(datasetSchemaProvider.getSchema(datasetId)).thenReturn(schema);

        TestCase testCase = TestCase.builder()
                .testCaseName("multi-turn-case")
                .data("{\"shared\":\"s\"}")
                .multiTurnData("[{\"prompt\":\"t0\"},{\"prompt\":\"t1\"}]")
                .build();
        stubOnePageOfCases(List.of(testCase));

        Map<String, byte[]> entries = buildAndReadZip();

        String csv = new String(entries.get("test-cases.csv"), StandardCharsets.UTF_8);
        String[] lines = csv.split("\n");
        assertThat(lines).hasSize(3); // header + 2 turn rows
        assertThat(lines[0]).isEqualTo("testCaseName,turnIndex,shared,prompt");
        assertThat(lines[1]).isEqualTo("multi-turn-case,0,s,t0");
        assertThat(lines[2]).isEqualTo("multi-turn-case,1,s,t1");
    }

    @Test
    @DisplayName("manifest.json is always written, with the dataset schema, even without any FILE value")
    void buildZip_alwaysWritesManifest() throws IOException {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("document")
                .type(SchemaFieldType.FILE)
                .required(true)
                .build());
        when(datasetSchemaProvider.getSchema(datasetId)).thenReturn(schema);

        TestCase testCase =
                TestCase.builder().testCaseName("no-file-case").data("{}").build();
        stubOnePageOfCases(List.of(testCase));

        Map<String, byte[]> entries = buildAndReadZip();

        assertThat(entries).containsKey("manifest.json");
        ZipManifestSerializer manifestSerializer = new ZipManifestSerializer(
                new ObjectMapper(), Validation.buildDefaultValidatorFactory().getValidator());
        ZipManifest manifest = manifestSerializer.read(entries.get("manifest.json"));
        assertThat(manifest.formatVersion()).isEqualTo(ZipManifest.CURRENT_FORMAT_VERSION);
        assertThat(manifest.testCaseSchema()).hasSize(1);
        assertThat(manifest.testCaseSchema().getFirst().getName()).isEqualTo("document");
        assertThat(manifest.files()).isEmpty();
    }

    @Test
    @DisplayName("one EF-owned reference used by many rows and turns is downloaded once and shares one path")
    void buildZip_sameReferenceAcrossRowsAndTurns_downloadsOnceAndDedups() throws IOException {
        List<FieldDefinitionDto> schema = List.of(
                FieldDefinitionDto.builder()
                        .name("document")
                        .type(SchemaFieldType.FILE)
                        .build(),
                FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .perTurn(true)
                        .build());
        when(datasetSchemaProvider.getSchema(datasetId)).thenReturn(schema);
        when(dialFileClient.getBucket()).thenReturn(REAL_BUCKET);
        stubDownload("file bytes");

        String ref = BUCKET_ALIAS + "/datasets/" + datasetId + "/report.pdf";
        TestCase multiTurnCase = TestCase.builder()
                .testCaseName("case-1")
                .data("{\"document\":\"" + ref + "\"}")
                .multiTurnData("[{\"prompt\":\"t0\"},{\"prompt\":\"t1\"}]")
                .build();
        TestCase otherCase = TestCase.builder()
                .testCaseName("case-2")
                .data("{\"document\":\"" + ref + "\"}")
                .build();
        stubOnePageOfCases(List.of(multiTurnCase, otherCase));

        Map<String, byte[]> entries = buildAndReadZip();

        long fileEntryCount =
                entries.keySet().stream().filter(k -> k.startsWith("files/")).count();
        assertThat(fileEntryCount).isEqualTo(1);
        assertThat(entries).containsKey("files/1/report.pdf");
        assertThat(new String(entries.get("files/1/report.pdf"), StandardCharsets.UTF_8))
                .isEqualTo("file bytes");
        verify(dialFileClient, times(1)).downloadTo(anyString(), any());

        String csv = new String(entries.get("test-cases.csv"), StandardCharsets.UTF_8);
        assertThat(csv).doesNotContain(ref); // the raw ref must not leak into the CSV
        long occurrences = csv.split("files/1/report.pdf", -1).length - 1;
        assertThat(occurrences).isEqualTo(3); // 2 turn rows of case-1 + 1 row of case-2

        ZipManifestSerializer manifestSerializer = new ZipManifestSerializer(
                new ObjectMapper(), Validation.buildDefaultValidatorFactory().getValidator());
        ZipManifest manifest = manifestSerializer.read(entries.get("manifest.json"));
        assertThat(manifest.files()).hasSize(1);
        assertThat(manifest.files().getFirst().path()).isEqualTo("files/1/report.pdf");
        assertThat(manifest.files().getFirst().sourceRef()).isEqualTo(ref);
    }

    @Test
    @DisplayName("a download failure throws, names the reference, and deletes the temp ZIP file")
    void buildZip_downloadFailure_throwsAndDeletesTempFile() throws IOException {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("document")
                .type(SchemaFieldType.FILE)
                .build());
        when(datasetSchemaProvider.getSchema(datasetId)).thenReturn(schema);
        when(dialFileClient.getBucket()).thenReturn(REAL_BUCKET);

        String ref = BUCKET_ALIAS + "/datasets/" + datasetId + "/missing.pdf";
        doAnswer(invocation -> {
                    throw new DialCoreClientException(HttpStatus.NOT_FOUND, "not found");
                })
                .when(dialFileClient)
                .downloadTo(anyString(), any());

        TestCase testCase = TestCase.builder()
                .testCaseName("case-1")
                .data("{\"document\":\"" + ref + "\"}")
                .build();
        stubOnePageOfCases(List.of(testCase));

        long tempZipsBefore = countLeakedZipExportTempFiles();

        assertThatThrownBy(() -> service.buildZip(datasetId, List.of(), ','))
                .isInstanceOf(DialCoreClientException.class)
                .hasMessageContaining(ref);

        long tempZipsAfter = countLeakedZipExportTempFiles();
        assertThat(tempZipsAfter).isEqualTo(tempZipsBefore);
    }

    @Test
    @DisplayName(
            "a transport-level download failure (not a DialCoreClientException) is mapped to a 502 naming the reference, and deletes the temp ZIP file")
    void buildZip_transportFailureDuringDownload_mapsTo502AndDeletesTempFile() throws IOException {
        List<FieldDefinitionDto> schema = List.of(FieldDefinitionDto.builder()
                .name("document")
                .type(SchemaFieldType.FILE)
                .build());
        when(datasetSchemaProvider.getSchema(datasetId)).thenReturn(schema);
        when(dialFileClient.getBucket()).thenReturn(REAL_BUCKET);

        String ref = BUCKET_ALIAS + "/datasets/" + datasetId + "/unreachable.pdf";
        doAnswer(invocation -> {
                    throw new ResourceAccessException("Connection refused");
                })
                .when(dialFileClient)
                .downloadTo(anyString(), any());

        TestCase testCase = TestCase.builder()
                .testCaseName("case-1")
                .data("{\"document\":\"" + ref + "\"}")
                .build();
        stubOnePageOfCases(List.of(testCase));

        long tempZipsBefore = countLeakedZipExportTempFiles();

        assertThatThrownBy(() -> service.buildZip(datasetId, List.of(), ','))
                .isInstanceOf(DialCoreClientException.class)
                .hasMessageContaining(ref)
                .satisfies(e -> assertThat(((DialCoreClientException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));

        long tempZipsAfter = countLeakedZipExportTempFiles();
        assertThat(tempZipsAfter).isEqualTo(tempZipsBefore);
    }

    private void stubOnePageOfCases(List<TestCase> cases) {
        when(testCaseRepository.findAllByDatasetId(
                        org.mockito.ArgumentMatchers.eq(datasetId),
                        any(PageRequest.class),
                        any(),
                        org.mockito.ArgumentMatchers.eq(false)))
                .thenAnswer(invocation -> {
                    PageRequest pageRequest = invocation.getArgument(1);
                    if (pageRequest.getPage() > 0) {
                        return Page.<TestCase>builder().content(List.of()).build();
                    }
                    return Page.<TestCase>builder().content(cases).build();
                });
    }

    private void stubDownload(String content) {
        doAnswer(invocation -> {
                    OutputStream out = invocation.getArgument(1);
                    out.write(content.getBytes(StandardCharsets.UTF_8));
                    return null;
                })
                .when(dialFileClient)
                .downloadTo(anyString(), any());
    }

    private Map<String, byte[]> buildAndReadZip() throws IOException {
        try (ZipExportService.ZipExportHandle handle = service.buildZip(datasetId, List.of(), ',')) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            handle.transferTo(out);
            return readZipEntries(out.toByteArray());
        }
    }

    private static Map<String, byte[]> readZipEntries(byte[] zipBytes) throws IOException {
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

    private static long countLeakedZipExportTempFiles() throws IOException {
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        long count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmpDir, "zip-export-*.zip")) {
            for (Path ignored : stream) {
                count++;
            }
        }
        return count;
    }
}
