package com.epam.aidial.evaluation.service.domain.zip;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportWarningDto;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Scans and rewrites {@code test-cases.csv} cell by cell (design D5), using Commons CSV so quoting and
 * delimiter handling are correct rather than approximated with a regex anchored to cell boundaries.
 *
 * <p>A cell counts as an archive file path only when all of these hold: its whole value, trimmed, starts
 * with {@code files/}; its column is not {@code testCaseName} or {@code turnIndex}; and its column's
 * resolved type ({@link ZipImportColumnTypeResolver.ColumnTypeResolution#eligibleForRewrite()}) is {@code
 * FILE} or undetermined, and not excluded. Every other cell — including a STRING column's cell that merely
 * contains {@code files/…} inside a longer value — passes through unchanged.
 */
@Component
@LogExecution
public class ZipCsvFileRefRewriter {

    private static final String TEST_CASE_NAME_HEADER = "testCaseName";
    private static final String TURN_INDEX_HEADER = "turnIndex";

    /**
     * Returns the archive {@code files/…} paths referenced by eligible cells, and the set of column names in
     * which at least one such reference was found. Makes no changes; used both to feed {@link
     * ZipFileImportPlanner} and, absent a manifest, to derive the FILE-hint columns for {@code
     * CsvImportSchemaHints}.
     */
    public ScanResult scan(
            byte[] csv, char delimiter, Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> columnTypes) {
        Set<String> referencedPaths = new LinkedHashSet<>();
        Set<String> fileColumns = new LinkedHashSet<>();
        try (CSVParser parser = createParser(csv, delimiter)) {
            Iterator<CSVRecord> it = parser.iterator();
            if (!it.hasNext()) {
                return new ScanResult(Set.of(), Set.of());
            }
            List<String> headers = headerNames(it.next());
            while (it.hasNext()) {
                CSVRecord record = it.next();
                for (int i = 0; i < headers.size() && i < record.size(); i++) {
                    String header = headers.get(i);
                    if (!isEligibleColumn(header, columnTypes)) {
                        continue;
                    }
                    String value = record.get(i).trim();
                    if (value.startsWith(ZipArchiveReader.FILES_PREFIX)) {
                        referencedPaths.add(value);
                        fileColumns.add(header);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan ZIP-imported CSV for file references", e);
        }
        return new ScanResult(referencedPaths, fileColumns);
    }

    /**
     * Rewrites eligible cells: a path present in {@code pathToRef} becomes that reference; a path absent
     * from it becomes a blank cell plus a warning naming the row (header = row 1, CSV import convention) and
     * column. Every other cell is copied through unchanged.
     */
    public RewriteResult rewrite(
            byte[] csv,
            char delimiter,
            Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> columnTypes,
            Map<String, String> pathToRef) {
        List<CsvImportWarningDto> warnings = new ArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (CSVParser parser = createParser(csv, delimiter);
                CSVPrinter printer =
                        new CSVPrinter(new OutputStreamWriter(out, StandardCharsets.UTF_8), format(delimiter))) {
            Iterator<CSVRecord> it = parser.iterator();
            if (!it.hasNext()) {
                return new RewriteResult(csv, List.of());
            }
            List<String> headers = headerNames(it.next());
            printer.printRecord(headers);

            int rowNumber = 1;
            while (it.hasNext()) {
                CSVRecord record = it.next();
                rowNumber++;
                List<String> values = new ArrayList<>(headers.size());
                for (int i = 0; i < headers.size(); i++) {
                    String header = headers.get(i);
                    String rawValue = i < record.size() ? record.get(i) : "";
                    if (isEligibleColumn(header, columnTypes)) {
                        String trimmed = rawValue.trim();
                        if (trimmed.startsWith(ZipArchiveReader.FILES_PREFIX)) {
                            String ref = pathToRef.get(trimmed);
                            if (ref != null) {
                                values.add(ref);
                            } else {
                                values.add("");
                                warnings.add(CsvImportWarningDto.builder()
                                        .rowNumber(rowNumber)
                                        .columnName(header)
                                        .message("File missing from archive: " + trimmed)
                                        .build());
                            }
                            continue;
                        }
                    }
                    values.add(rawValue);
                }
                printer.printRecord(values);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to rewrite ZIP-imported CSV", e);
        }
        return new RewriteResult(out.toByteArray(), warnings);
    }

    private static boolean isEligibleColumn(
            String header, Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> columnTypes) {
        if (TEST_CASE_NAME_HEADER.equalsIgnoreCase(header) || TURN_INDEX_HEADER.equalsIgnoreCase(header)) {
            return false;
        }
        ZipImportColumnTypeResolver.ColumnTypeResolution resolution = columnTypes.get(header);
        // No resolution supplied for this column: treat it like an undetermined column (eligible),
        // consistent with the resolver's own default when neither the dataset nor the manifest types it.
        return resolution == null || resolution.eligibleForRewrite();
    }

    private static List<String> headerNames(CSVRecord record) {
        List<String> headers = new ArrayList<>(record.size());
        for (int i = 0; i < record.size(); i++) {
            headers.add(record.get(i).trim());
        }
        return headers;
    }

    private static CSVParser createParser(byte[] csv, char delimiter) throws IOException {
        return CSVParser.builder()
                .setFormat(format(delimiter))
                .setReader(new InputStreamReader(new ByteArrayInputStream(csv), StandardCharsets.UTF_8))
                .get();
    }

    private static CSVFormat format(char delimiter) {
        return CSVFormat.DEFAULT
                .builder()
                .setDelimiter(delimiter)
                .setQuote('"')
                .setTrim(true)
                .setIgnoreEmptyLines(false)
                .get();
    }

    /** {@code referencedPaths}: every archive path an eligible cell held. {@code fileColumns}: their columns. */
    public record ScanResult(Set<String> referencedPaths, Set<String> fileColumns) {}

    /** The rewritten CSV bytes and any missing-file warnings raised while rewriting. */
    public record RewriteResult(byte[] csv, List<CsvImportWarningDto> warnings) {}
}
