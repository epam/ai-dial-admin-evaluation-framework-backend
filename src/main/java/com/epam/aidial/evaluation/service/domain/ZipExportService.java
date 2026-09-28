package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.csv.CsvExportProperties;
import com.epam.aidial.evaluation.configuration.properties.pagination.PaginationProperties;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.csv.TestCaseExportRowProjector;
import com.epam.aidial.evaluation.service.domain.csv.TestCaseExportRowProjector.ProjectedRow;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.zip.ZipExportFileCollector;
import com.epam.aidial.evaluation.service.domain.zip.ZipExportFileCollector.FileClassification;
import com.epam.aidial.evaluation.service.domain.zip.ZipManifest;
import com.epam.aidial.evaluation.service.domain.zip.ZipManifestSerializer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Exports test cases as a ZIP archive (test-cases.csv, manifest.json and a files/ directory) when the
 * dataset's schema has FILE-type fields and materialization is requested. Builds the whole archive on disk
 * before returning a handle to it (design D8), so a download failure never sends a partial ZIP with a 200
 * status.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class ZipExportService {

    private static final String TEST_CASES_CSV_ENTRY = "test-cases.csv";
    private static final String MANIFEST_JSON_ENTRY = "manifest.json";

    private final DatasetRepository datasetRepository;
    private final DatasetSchemaProvider datasetSchemaProvider;
    private final TestCaseRepository testCaseRepository;
    private final DialFileClient dialFileClient;
    private final ZipExportFileCollector zipExportFileCollector;
    private final ZipManifestSerializer zipManifestSerializer;
    private final FilterParser filterParser;
    private final ObjectMapper objectMapper;
    private final CsvExportProperties csvExportProperties;
    private final PaginationProperties paginationProperties;
    private final TestCaseExportRowProjector rowProjector;

    /**
     * Returns true if the dataset's schema contains at least one FILE-type field.
     */
    public boolean hasFileFields(UUID datasetId) {
        if (!datasetRepository.existsById(datasetId)) {
            throw new EntityNotFoundException("Dataset not found: " + datasetId);
        }
        List<FieldDefinitionDto> fields = datasetSchemaProvider.getSchema(datasetId);
        return fields.stream().anyMatch(f -> f != null && f.getType() == SchemaFieldType.FILE);
    }

    /**
     * Builds a complete ZIP export for the dataset in a temp file and returns a handle to it. Only after
     * this method returns successfully has every referenced EF-owned file been downloaded and every row
     * written, so a caller can set response headers only on success (see design D8).
     *
     * @throws EntityNotFoundException if the dataset does not exist
     * @throws DialCoreClientException naming the reference, if any EF-owned file cannot be downloaded
     */
    public ZipExportHandle buildZip(UUID datasetId, List<String> filter, char delimiter) {
        if (!datasetRepository.existsById(datasetId)) {
            throw new EntityNotFoundException("Dataset not found: " + datasetId);
        }

        List<FieldDefinitionDto> fields = datasetSchemaProvider.getSchema(datasetId);
        List<String> dataColumnNames = fields.stream()
                .map(FieldDefinitionDto::getName)
                .filter(name -> name != null && !name.isBlank())
                .toList();
        Set<String> fileFieldNames = fields.stream()
                .filter(f -> f != null && f.getType() == SchemaFieldType.FILE)
                .map(FieldDefinitionDto::getName)
                .collect(Collectors.toSet());

        List<FilterCondition> filters = filterParser.parse(filter != null ? filter : List.of());
        int pageSize = Math.clamp(csvExportProperties.getPageSize(), 1, paginationProperties.getMaxSize());

        Path csvPath = createTempFile("zip-export-csv-", ".tmp");
        Path zipPath;
        try {
            zipPath = createTempFile("zip-export-", ".zip");
        } catch (RuntimeException e) {
            deleteQuietly(csvPath);
            throw e;
        }

        try {
            Map<String, String> assignedArchivePaths = new LinkedHashMap<>();
            List<ZipManifest.FileEntry> manifestFiles = new ArrayList<>();

            try (OutputStream zipOut = Files.newOutputStream(zipPath);
                    ZipOutputStream zos = new ZipOutputStream(zipOut, StandardCharsets.UTF_8)) {
                writeCsvAndFileEntries(
                        zos,
                        csvPath,
                        datasetId,
                        dataColumnNames,
                        fileFieldNames,
                        delimiter,
                        pageSize,
                        filters,
                        assignedArchivePaths,
                        manifestFiles);

                ZipManifest manifest = new ZipManifest(ZipManifest.CURRENT_FORMAT_VERSION, fields, manifestFiles);
                zos.putNextEntry(new ZipEntry(MANIFEST_JSON_ENTRY));
                zos.write(zipManifestSerializer.write(manifest));
                zos.closeEntry();
            }

            Path builtZipPath = zipPath;
            zipPath = null; // ownership passes to the handle
            return new TempFileZipExportHandle(builtZipPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to build ZIP export for dataset " + datasetId, e);
        } finally {
            deleteQuietly(csvPath);
            deleteQuietly(zipPath);
        }
    }

    private void writeCsvAndFileEntries(
            ZipOutputStream zos,
            Path csvPath,
            UUID datasetId,
            List<String> dataColumnNames,
            Set<String> fileFieldNames,
            char delimiter,
            int pageSize,
            List<FilterCondition> filters,
            Map<String, String> assignedArchivePaths,
            List<ZipManifest.FileEntry> manifestFiles)
            throws IOException {
        CSVFormat format = CSVFormat.DEFAULT
                .builder()
                .setDelimiter(delimiter)
                .setRecordSeparator("\n")
                .get();

        try (OutputStreamWriter writer =
                        new OutputStreamWriter(Files.newOutputStream(csvPath), StandardCharsets.UTF_8);
                CSVPrinter printer = new CSVPrinter(writer, format)) {
            List<String> header = new ArrayList<>();
            header.add("testCaseName");
            header.add("turnIndex");
            header.addAll(dataColumnNames);
            printer.printRecord(header);

            int page = 0;
            while (true) {
                PageRequest pageRequest = PageRequest.of(page, pageSize);
                Page<TestCase> pageResult =
                        testCaseRepository.findAllByDatasetId(datasetId, pageRequest, filters, false);
                List<TestCase> cases = pageResult.getContent();
                if (cases.isEmpty()) {
                    break;
                }
                for (TestCase tc : cases) {
                    String name = tc.getTestCaseName() != null ? tc.getTestCaseName() : "";
                    for (ProjectedRow row : rowProjector.project(tc)) {
                        printer.printRecord(buildRow(
                                name,
                                row.turnIndex(),
                                row.data(),
                                dataColumnNames,
                                fileFieldNames,
                                zos,
                                assignedArchivePaths,
                                manifestFiles));
                    }
                }
                if (cases.size() < pageSize) {
                    break;
                }
                page++;
            }
        }

        zos.putNextEntry(new ZipEntry(TEST_CASES_CSV_ENTRY));
        Files.copy(csvPath, zos);
        zos.closeEntry();
    }

    private List<Object> buildRow(
            String testCaseName,
            String turnIndex,
            Map<String, Object> data,
            List<String> dataColumnNames,
            Set<String> fileFieldNames,
            ZipOutputStream zos,
            Map<String, String> assignedArchivePaths,
            List<ZipManifest.FileEntry> manifestFiles)
            throws IOException {
        List<Object> row = new ArrayList<>();
        row.add(testCaseName);
        row.add(turnIndex);
        for (String name : dataColumnNames) {
            Object value = data.get(name);
            if (fileFieldNames.contains(name) && value != null) {
                row.add(resolveFileCell(value.toString(), zos, assignedArchivePaths, manifestFiles));
            } else {
                row.add(cellValue(value));
            }
        }
        return row;
    }

    private String resolveFileCell(
            String ref,
            ZipOutputStream zos,
            Map<String, String> assignedArchivePaths,
            List<ZipManifest.FileEntry> manifestFiles)
            throws IOException {
        FileClassification classification = zipExportFileCollector.classify(ref, assignedArchivePaths);
        if (classification instanceof FileClassification.Verbatim verbatim) {
            return verbatim.value() != null ? verbatim.value() : "";
        }

        FileClassification.EfOwned efOwned = (FileClassification.EfOwned) classification;
        if (efOwned.firstSeen()) {
            zos.putNextEntry(new ZipEntry(efOwned.archivePath()));
            try {
                dialFileClient.downloadTo(efOwned.realPath(), zos);
            } catch (DialCoreClientException e) {
                log.warn("Failed to download file for ZIP export, aborting: ref={}, error={}", ref, e.getMessage(), e);
                throw new DialCoreClientException(
                        e.getStatusCode(), "Failed to download file for ZIP export: " + ref, e);
            } catch (RestClientException e) {
                // Transport-level failure (e.g. ResourceAccessException) that DialFileClient does not itself
                // map to a DialCoreClientException; still must fail the export naming the ref, not surface
                // as a generic 500 with no ref.
                log.warn("Failed to download file for ZIP export, aborting: ref={}, error={}", ref, e.getMessage(), e);
                throw new DialCoreClientException(
                        HttpStatus.BAD_GATEWAY, "Failed to download file for ZIP export: " + ref, e);
            }
            zos.closeEntry();
            manifestFiles.add(new ZipManifest.FileEntry(efOwned.archivePath(), ref));
        }
        return efOwned.archivePath();
    }

    private String cellValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List || value instanceof Map) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JacksonException e) {
                throw new IllegalStateException("Failed to serialize cell value to JSON", e);
            }
        }
        return value.toString();
    }

    private static Path createTempFile(String prefix, String suffix) {
        try {
            return Files.createTempFile(prefix, suffix);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create temp file for ZIP export", e);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temp file {}: {}", path, e.getMessage(), e);
        }
    }

    /**
     * A handle to a finished, on-disk ZIP export. {@link #close()} deletes the temp file, so callers stream
     * it through try-with-resources.
     */
    public interface ZipExportHandle extends AutoCloseable {

        /**
         * Copies the built ZIP's bytes to {@code out}.
         */
        void transferTo(OutputStream out) throws IOException;

        @Override
        void close();
    }

    private final class TempFileZipExportHandle implements ZipExportHandle {

        private final Path zipPath;

        private TempFileZipExportHandle(Path zipPath) {
            this.zipPath = zipPath;
        }

        @Override
        public void transferTo(OutputStream out) throws IOException {
            Files.copy(zipPath, out);
        }

        @Override
        public void close() {
            deleteQuietly(zipPath);
        }
    }
}
