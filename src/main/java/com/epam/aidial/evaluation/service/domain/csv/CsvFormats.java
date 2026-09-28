package com.epam.aidial.evaluation.service.domain.csv;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;

/**
 * The one definition of the test-case CSV dialect. Import, the ZIP pre-import scan/rewrite and export all
 * use it, so a cell the ZIP rewriter sees is the same cell {@code CsvImportService} later parses.
 */
public final class CsvFormats {

    private CsvFormats() {}

    /** Reading test-case CSV: {@code "} quotes, cells trimmed, empty lines kept (they count as rows). */
    public static CSVFormat forImport(char delimiter) {
        return CSVFormat.DEFAULT
                .builder()
                .setDelimiter(delimiter)
                .setQuote('"')
                .setTrim(true)
                .setIgnoreEmptyLines(false)
                .get();
    }

    /** Writing test-case CSV export: {@code \n} record separator. */
    public static CSVFormat forExport(char delimiter) {
        return CSVFormat.DEFAULT
                .builder()
                .setDelimiter(delimiter)
                .setRecordSeparator("\n")
                .get();
    }

    /** A UTF-8 parser over {@code in} in the {@link #forImport import} dialect. */
    public static CSVParser importParser(InputStream in, char delimiter) throws IOException {
        return CSVParser.builder()
                .setFormat(forImport(delimiter))
                .setReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                .get();
    }

    /** A UTF-8 parser over {@code csv} in the {@link #forImport import} dialect. */
    public static CSVParser importParser(byte[] csv, char delimiter) throws IOException {
        return importParser(new ByteArrayInputStream(csv), delimiter);
    }
}
