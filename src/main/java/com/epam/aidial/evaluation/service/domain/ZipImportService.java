package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvConflictStrategy;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportMode;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportPreviewDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportWarningDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Handles import of test cases from ZIP archives containing test-cases.csv and a files/ directory.
 * Files in the archive are uploaded to DIAL Core file storage and their DIAL references replace
 * the relative paths in CSV cells.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class ZipImportService {

    private static final Pattern VALID_FILENAME_CHARS = Pattern.compile("[^a-zA-Z0-9\\-_. ()]");
    private static final int MAX_FILENAME_LENGTH = 255;

    private final CsvImportService csvImportService;
    private final DialFileClient dialFileClient;
    private final DialFileRefResolver dialFileRefResolver;

    /**
     * Detects whether the input is a ZIP archive (by checking magic bytes).
     */
    public boolean isZipArchive(InputStream input) throws IOException {
        input.mark(4);
        byte[] header = new byte[4];
        int bytesRead = input.read(header);
        input.reset();
        return bytesRead == 4 && header[0] == 0x50 && header[1] == 0x4B && header[2] == 0x03 && header[3] == 0x04;
    }

    /**
     * Preview ZIP import: extracts CSV, resolves file paths, delegates to CsvImportService preview.
     */
    public CsvImportPreviewDto previewZip(
            UUID datasetId,
            InputStream zipInput,
            long fileSize,
            char delimiter,
            CsvImportMode importMode,
            CsvConflictStrategy conflictStrategy)
            throws IOException {
        ZipContent content = extractZipContent(zipInput);
        if (content.csvData == null) {
            throw new ValidationException("ZIP archive must contain a test-cases.csv file");
        }

        return csvImportService.preview(
                datasetId,
                new ByteArrayInputStream(content.csvData),
                content.csvData.length,
                delimiter,
                importMode,
                conflictStrategy);
    }

    /**
     * Import ZIP: extracts CSV, uploads files to DIAL Core file storage, replaces file paths
     * with DIAL file references, then delegates to CsvImportService.
     */
    public CsvImportResultDto importZip(
            UUID datasetId,
            InputStream zipInput,
            long fileSize,
            char delimiter,
            Long expectedVersion,
            CsvImportMode importMode,
            CsvConflictStrategy conflictStrategy)
            throws IOException {
        ZipContent content = extractZipContent(zipInput);
        if (content.csvData == null) {
            throw new ValidationException("ZIP archive must contain a test-cases.csv file");
        }

        Map<String, String> pathToDialRef = new HashMap<>();
        List<CsvImportWarningDto> fileWarnings = new ArrayList<>();
        Set<String> usedFilenames = new HashSet<>();

        for (Map.Entry<String, byte[]> entry : content.files.entrySet()) {
            String archivePath = entry.getKey();
            byte[] fileBytes = entry.getValue();
            String originalFilename = Paths.get(archivePath).getFileName().toString();
            String sanitized = sanitizeFilename(originalFilename);
            String uniqueFilename = generateUniqueFilename(sanitized, usedFilenames);
            usedFilenames.add(uniqueFilename);

            String contentType = URLConnection.guessContentTypeFromName(originalFilename);
            if (contentType == null) {
                contentType = "application/octet-stream";
            }

            String efRef = dialFileRefResolver.buildDatasetEfRef(datasetId, uniqueFilename);
            String realPath = dialFileRefResolver.resolveToRealPath(efRef);
            dialFileClient.upload(realPath, new ByteArrayInputStream(fileBytes), uniqueFilename, contentType);
            pathToDialRef.put(archivePath, efRef);
        }

        byte[] rewrittenCsv = rewriteCsvFilePaths(content.csvData, pathToDialRef, fileWarnings);

        CsvImportResultDto result = csvImportService.importCsv(
                datasetId,
                new ByteArrayInputStream(rewrittenCsv),
                rewrittenCsv.length,
                delimiter,
                expectedVersion,
                importMode,
                conflictStrategy);

        if (!fileWarnings.isEmpty()) {
            List<CsvImportWarningDto> allWarnings = new ArrayList<>(result.getWarnings());
            allWarnings.addAll(fileWarnings);
            return CsvImportResultDto.builder()
                    .totalRows(result.getTotalRows())
                    .validCount(result.getValidCount())
                    .invalidCount(result.getInvalidCount())
                    .skippedCount(result.getSkippedCount())
                    .overriddenCount(result.getOverriddenCount())
                    .warnings(allWarnings)
                    .build();
        }
        return result;
    }

    private static final Pattern FILE_PATH_PATTERN = Pattern.compile("files/\\d+/[^,\\n\\r\"]+");

    /**
     * Rewrites CSV data: replaces relative file path references (files/rowIndex/...) with
     * DIAL file references. Paths not found in the archive are replaced with empty string
     * and a warning is generated.
     */
    private byte[] rewriteCsvFilePaths(
            byte[] csvData, Map<String, String> pathToDialRef, List<CsvImportWarningDto> warnings) {
        String csv = new String(csvData, StandardCharsets.UTF_8);

        for (Map.Entry<String, String> entry : pathToDialRef.entrySet()) {
            csv = csv.replace(entry.getKey(), entry.getValue());
        }

        Matcher matcher = FILE_PATH_PATTERN.matcher(csv);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String missingPath = matcher.group();
            warnings.add(CsvImportWarningDto.builder()
                    .rowNumber(0)
                    .columnName("FILE")
                    .message("File missing from archive: " + missingPath)
                    .build());
            matcher.appendReplacement(result, "");
        }
        matcher.appendTail(result);

        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private ZipContent extractZipContent(InputStream input) throws IOException {
        byte[] csvData = null;
        Map<String, byte[]> files = new HashMap<>();

        try (ZipInputStream zis = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                byte[] data = readEntryBytes(zis);

                if ("test-cases.csv".equals(name)) {
                    csvData = data;
                } else if (name.startsWith("files/")) {
                    files.put(name, data);
                }
                zis.closeEntry();
            }
        }

        return new ZipContent(csvData, files);
    }

    private byte[] readEntryBytes(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = zis.read(buffer)) != -1) {
            bos.write(buffer, 0, len);
        }
        return bos.toByteArray();
    }

    static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unnamed";
        }
        String sanitized = VALID_FILENAME_CHARS.matcher(filename).replaceAll("_");
        sanitized = sanitized.trim();
        if (sanitized.isEmpty()) {
            return "unnamed";
        }
        if (sanitized.length() > MAX_FILENAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_FILENAME_LENGTH);
        }
        return sanitized;
    }

    static String generateUniqueFilename(String filename, Set<String> usedFilenames) {
        if (!usedFilenames.contains(filename)) {
            return filename;
        }
        int dotIndex = filename.lastIndexOf('.');
        String base = dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
        String ext = dotIndex > 0 ? filename.substring(dotIndex) : "";
        int counter = 1;
        while (true) {
            String candidate = base + "_" + counter + ext;
            if (!usedFilenames.contains(candidate)) {
                return candidate;
            }
            counter++;
        }
    }

    private record ZipContent(byte[] csvData, Map<String, byte[]> files) {}
}
