package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.csv.CsvImportProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.csv.CsvFormats;
import com.epam.aidial.evaluation.service.domain.csv.CsvImportSchemaHints;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvConflictStrategy;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportMode;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportPreviewDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportWarningDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.exception.VersionConflictException;
import com.epam.aidial.evaluation.service.domain.zip.ZipArchiveReader;
import com.epam.aidial.evaluation.service.domain.zip.ZipCsvFileRefRewriter;
import com.epam.aidial.evaluation.service.domain.zip.ZipFileImportPlanner;
import com.epam.aidial.evaluation.service.domain.zip.ZipImportColumnTypeResolver;
import com.epam.aidial.evaluation.service.domain.zip.ZipImportUploadJournal;
import com.epam.aidial.evaluation.service.domain.zip.ZipManifest;
import com.epam.aidial.evaluation.service.domain.zip.ZipManifestSerializer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLConnection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

/**
 * Coordinates ZIP import and preview (design D6/D7): opens the staged archive with {@link
 * ZipArchiveReader}, resolves each CSV data column's type with {@link ZipImportColumnTypeResolver}, scans
 * and rewrites {@code test-cases.csv} cell by cell with {@link ZipCsvFileRefRewriter}, plans file names and
 * overwrite/new status with {@link ZipFileImportPlanner}, then — import only — uploads each planned file via
 * {@link FileService#putDatasetFile}, recording every write in a {@link ZipImportUploadJournal} so a failed
 * import can be rolled back, before delegating to {@link CsvImportService}.
 *
 * <p>Preview runs the same pipeline through the rewrite and pre-write checks, but performs no DIAL write.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class ZipImportService {

    private static final String TEST_CASE_NAME_HEADER = "testCaseName";
    private static final String TURN_INDEX_HEADER = "turnIndex";

    private final ZipArchiveReader zipArchiveReader;
    private final ZipManifestSerializer zipManifestSerializer;
    private final ZipCsvFileRefRewriter zipCsvFileRefRewriter;
    private final ZipImportColumnTypeResolver zipImportColumnTypeResolver;
    private final ZipFileImportPlanner zipFileImportPlanner;
    private final DatasetSchemaProvider datasetSchemaProvider;
    private final DatasetService datasetService;
    private final FileService fileService;
    private final CsvImportService csvImportService;
    private final CsvImportProperties csvImportProperties;

    /**
     * Detects whether the input is a ZIP archive (by checking magic bytes), used by the controller as a
     * fallback when the file extension and content type don't already say so (design D9).
     */
    public boolean isZipArchive(InputStream input) throws IOException {
        input.mark(4);
        byte[] header = new byte[4];
        int bytesRead = input.read(header);
        input.reset();
        return bytesRead == 4 && header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04;
    }

    /**
     * Preview ZIP import: runs the same pipeline as {@link #importZip} through the rewrite and pre-write
     * checks (no {@code If-Match} check; {@code stagedFile} is the caller's temp copy of the upload, which the
     * caller deletes), but makes no DIAL write. FILE cells in the resulting sample rows show the future
     * {@code @ef/datasets/{id}/{name}} references (design D7).
     */
    public CsvImportPreviewDto previewZip(
            UUID datasetId,
            Path stagedFile,
            char delimiter,
            CsvImportMode importMode,
            CsvConflictStrategy conflictStrategy) {
        try (ZipArchiveReader.OpenZip openZip = zipArchiveReader.open(stagedFile)) {
            PreparedImport prepared = prepare(datasetId, openZip, delimiter, importMode);
            CsvImportPreviewDto preview = csvImportService.preview(
                    datasetId,
                    new ByteArrayInputStream(prepared.rewrittenCsv()),
                    prepared.rewrittenCsv().length,
                    delimiter,
                    importMode,
                    conflictStrategy,
                    prepared.hints());
            return mergePreviewWarnings(preview, prepared.rewriteWarnings());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read staged ZIP archive", e);
        }
    }

    /**
     * Import ZIP: opens the staged archive, resolves column types, scans and plans referenced files,
     * rewrites the CSV, checks the rewritten CSV's size/row count and (when {@code expectedVersion} is
     * given) the dataset's current version before any DIAL write, uploads each planned file (backing up one
     * about to be overwritten first) and records every write in a journal, then delegates to {@link
     * CsvImportService#importCsv}. On any {@link RuntimeException} the journal is rolled back and the
     * exception rethrown (design D6/D11).
     */
    public CsvImportResultDto importZip(
            UUID datasetId,
            Path stagedFile,
            char delimiter,
            Long expectedVersion,
            CsvImportMode importMode,
            CsvConflictStrategy conflictStrategy) {
        ZipImportUploadJournal journal = new ZipImportUploadJournal(datasetId, fileService);
        try (ZipArchiveReader.OpenZip openZip = zipArchiveReader.open(stagedFile)) {
            PreparedImport prepared = prepare(datasetId, openZip, delimiter, importMode);

            if (expectedVersion != null) {
                Long currentVersion = datasetService.getById(datasetId).getVersion();
                if (!expectedVersion.equals(currentVersion)) {
                    throw new VersionConflictException(
                            "Dataset version conflict: expected " + expectedVersion + " but current is "
                                    + currentVersion,
                            datasetId,
                            expectedVersion);
                }
            }

            uploadPlannedFiles(datasetId, openZip, prepared, journal);

            CsvImportResultDto result = csvImportService.importCsv(
                    datasetId,
                    new ByteArrayInputStream(prepared.rewrittenCsv()),
                    prepared.rewrittenCsv().length,
                    delimiter,
                    expectedVersion,
                    importMode,
                    conflictStrategy,
                    prepared.hints());

            return mergeResultWarnings(result, prepared.rewriteWarnings());
        } catch (RuntimeException e) {
            // Best effort even when nothing was written yet (e.g. a 400 raised before any upload): the
            // journal is then empty and rollback is a no-op.
            journal.rollback();
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read staged ZIP archive", e);
        } finally {
            journal.close();
        }
    }

    /**
     * Runs the pipeline shared by import and preview: reads the optional manifest, resolves each data
     * column's type, scans the CSV for referenced archive paths, validates and plans those actually present
     * in the archive, rewrites the CSV, and checks the rewritten CSV's size and row count. Makes one
     * read-only {@link FileService#listByDataset} call (inside {@link ZipFileImportPlanner#plan}); no write.
     */
    private PreparedImport prepare(
            UUID datasetId, ZipArchiveReader.OpenZip openZip, char delimiter, CsvImportMode importMode) {
        byte[] csvBytes = openZip.csvBytes();
        Optional<ZipManifest> manifest = openZip.manifestBytes().map(zipManifestSerializer::read);

        List<String> dataColumnNames = csvDataColumnNames(csvBytes, delimiter);
        List<FieldDefinitionDto> datasetSchema = datasetSchemaProvider.getSchema(datasetId);
        List<FieldDefinitionDto> manifestSchema =
                manifest.map(ZipManifest::testCaseSchema).orElse(List.of());
        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> columnTypes =
                zipImportColumnTypeResolver.resolve(importMode, datasetSchema, manifestSchema, dataColumnNames);

        ZipCsvFileRefRewriter.ScanResult scanResult = zipCsvFileRefRewriter.scan(csvBytes, delimiter, columnTypes);

        Set<String> existingReferenced = new LinkedHashSet<>(scanResult.referencedPaths());
        existingReferenced.retainAll(openZip.filePaths());
        openZip.validateReferencedEntries(existingReferenced);

        Map<String, String> manifestSourceRefByPath = manifestSourceRefByPath(manifest);
        ZipFileImportPlanner.ImportFilePlan plan =
                zipFileImportPlanner.plan(datasetId, existingReferenced, manifestSourceRefByPath);

        ZipCsvFileRefRewriter.RewriteResult rewriteResult =
                zipCsvFileRefRewriter.rewrite(csvBytes, delimiter, columnTypes, plan.pathToRef());

        validateRewrittenCsv(rewriteResult.csv(), delimiter);

        CsvImportSchemaHints hints = manifest.map(m -> new CsvImportSchemaHints(m.testCaseSchema(), Set.<String>of()))
                .orElseGet(() -> new CsvImportSchemaHints(List.of(), scanResult.fileColumns()));

        return new PreparedImport(
                rewriteResult.csv(), hints, rewriteResult.warnings(), plan, manifestContentTypeByPath(manifest));
    }

    private static Map<String, String> manifestContentTypeByPath(Optional<ZipManifest> manifest) {
        Map<String, String> byPath = new LinkedHashMap<>();
        manifest.ifPresent(m -> m.files().stream()
                .filter(entry ->
                        entry.contentType() != null && !entry.contentType().isBlank())
                .forEach(entry -> byPath.put(entry.path(), entry.contentType())));
        return byPath;
    }

    private static Map<String, String> manifestSourceRefByPath(Optional<ZipManifest> manifest) {
        if (manifest.isEmpty()) {
            return Map.of();
        }
        Map<String, String> byPath = new LinkedHashMap<>();
        for (ZipManifest.FileEntry entry : manifest.get().files()) {
            byPath.put(entry.path(), entry.sourceRef());
        }
        return byPath;
    }

    private void validateRewrittenCsv(byte[] csv, char delimiter) {
        long maxSize = csvImportProperties.getMaxFileSize().toBytes();
        if (csv.length > maxSize) {
            throw new ValidationException("Rewritten CSV size " + csv.length + " exceeds maximum of " + maxSize
                    + " bytes (ZIP file references expand into longer dataset refs)");
        }
        int dataRows = countDataRows(csv, delimiter);
        int maxRows = csvImportProperties.getMaxRows();
        if (dataRows > maxRows) {
            throw new ValidationException("CSV row count " + dataRows + " exceeds maximum of " + maxRows + " rows");
        }
    }

    private void uploadPlannedFiles(
            UUID datasetId, ZipArchiveReader.OpenZip openZip, PreparedImport prepared, ZipImportUploadJournal journal) {
        for (Map.Entry<String, ZipFileImportPlanner.PlannedFile> entry :
                prepared.plan().byPath().entrySet()) {
            String path = entry.getKey();
            ZipFileImportPlanner.PlannedFile planned = entry.getValue();
            String contentType = contentTypeFor(path, planned.filename(), prepared.contentTypeByPath());
            if (!planned.isNew()) {
                // Must succeed before the overwrite; a failure here aborts before this file is touched.
                journal.backupBeforeOverwrite(planned.filename());
            }
            try (InputStream in = openZip.openFile(path)) {
                fileService.putDatasetFile(datasetId, planned.filename(), in, contentType);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read archive file: " + path, e);
            }
            if (planned.isNew()) {
                journal.recordCreated(planned.filename());
            }
        }
    }

    /**
     * The manifest's recorded content type for this archive path when it is a valid media type, else a
     * guess from the filename, so a round trip keeps the source file's type.
     */
    private static String contentTypeFor(String path, String filename, Map<String, String> contentTypeByPath) {
        String recorded = contentTypeByPath.get(path);
        if (recorded != null) {
            try {
                return MediaType.parseMediaType(recorded).toString();
            } catch (InvalidMediaTypeException e) {
                log.warn("Ignoring invalid manifest content type for {}: {}", path, recorded, e);
            }
        }
        return guessContentType(filename);
    }

    private static String guessContentType(String filename) {
        String contentType = URLConnection.guessContentTypeFromName(filename);
        return contentType != null ? contentType : "application/octet-stream";
    }

    private static CsvImportResultDto mergeResultWarnings(
            CsvImportResultDto result, List<CsvImportWarningDto> rewriteWarnings) {
        if (rewriteWarnings.isEmpty()) {
            return result;
        }
        List<CsvImportWarningDto> merged = new ArrayList<>(rewriteWarnings);
        if (result.getWarnings() != null) {
            merged.addAll(result.getWarnings());
        }
        result.setWarnings(merged);
        return result;
    }

    private static CsvImportPreviewDto mergePreviewWarnings(
            CsvImportPreviewDto preview, List<CsvImportWarningDto> rewriteWarnings) {
        if (rewriteWarnings.isEmpty()) {
            return preview;
        }
        List<CsvImportWarningDto> merged = new ArrayList<>(rewriteWarnings);
        if (preview.getWarnings() != null) {
            merged.addAll(preview.getWarnings());
        }
        preview.setWarnings(merged);
        return preview;
    }

    /** The CSV's data column names (header row, excluding {@code testCaseName}/{@code turnIndex}). */
    private static List<String> csvDataColumnNames(byte[] csv, char delimiter) {
        try (CSVParser parser = CsvFormats.importParser(csv, delimiter)) {
            Iterator<CSVRecord> it = parser.iterator();
            if (!it.hasNext()) {
                return List.of();
            }
            CSVRecord headerRecord = it.next();
            List<String> names = new ArrayList<>();
            for (int i = 0; i < headerRecord.size(); i++) {
                String header = headerRecord.get(i).trim();
                if (!TEST_CASE_NAME_HEADER.equalsIgnoreCase(header) && !TURN_INDEX_HEADER.equalsIgnoreCase(header)) {
                    names.add(header);
                }
            }
            return names;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read ZIP-imported CSV headers", e);
        }
    }

    /** Number of data rows (excluding the header). Invariant across the cell-by-cell rewrite. */
    private static int countDataRows(byte[] csv, char delimiter) {
        try (CSVParser parser = CsvFormats.importParser(csv, delimiter)) {
            Iterator<CSVRecord> it = parser.iterator();
            if (!it.hasNext()) {
                return 0;
            }
            it.next();
            int count = 0;
            while (it.hasNext()) {
                it.next();
                count++;
            }
            return count;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to count ZIP-imported CSV rows", e);
        }
    }

    /**
     * Everything the shared pipeline produces: the rewritten CSV, its schema hints, warnings, the plan, and
     * the manifest's content type per archive path.
     */
    private record PreparedImport(
            byte[] rewrittenCsv,
            CsvImportSchemaHints hints,
            List<CsvImportWarningDto> rewriteWarnings,
            ZipFileImportPlanner.ImportFilePlan plan,
            Map<String, String> contentTypeByPath) {}
}
