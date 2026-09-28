package com.epam.aidial.evaluation.service.domain.zip;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportWarningDto;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ZipCsvFileRefRewriter")
class ZipCsvFileRefRewriterTest {

    private final ZipCsvFileRefRewriter rewriter = new ZipCsvFileRefRewriter();

    private static ZipImportColumnTypeResolver.ColumnTypeResolution fileType() {
        return new ZipImportColumnTypeResolver.ColumnTypeResolution(SchemaFieldType.FILE, false);
    }

    private static ZipImportColumnTypeResolver.ColumnTypeResolution stringType() {
        return new ZipImportColumnTypeResolver.ColumnTypeResolution(SchemaFieldType.STRING, false);
    }

    private static ZipImportColumnTypeResolver.ColumnTypeResolution excluded() {
        return new ZipImportColumnTypeResolver.ColumnTypeResolution(null, true);
    }

    private static byte[] bytes(String csv) {
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("scan finds referenced paths and their columns, rewrite replaces them with resolved refs")
    void scanAndRewrite_replaceReferencedPathsWithResolvedRefs() {
        byte[] csv = bytes("testCaseName,turnIndex,document\ncase1,,files/1/report.pdf\n");
        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> columnTypes = Map.of("document", fileType());

        ZipCsvFileRefRewriter.ScanResult scanResult = rewriter.scan(csv, ',', columnTypes);
        assertThat(scanResult.referencedPaths()).containsExactly("files/1/report.pdf");
        assertThat(scanResult.fileColumns()).containsExactly("document");

        ZipCsvFileRefRewriter.RewriteResult rewriteResult =
                rewriter.rewrite(csv, ',', columnTypes, Map.of("files/1/report.pdf", "@ef/datasets/abc/report.pdf"));

        String rewritten = new String(rewriteResult.csv(), StandardCharsets.UTF_8);
        assertThat(rewritten).contains("@ef/datasets/abc/report.pdf");
        assertThat(rewritten).doesNotContain("files/1/report.pdf");
        assertThat(rewriteResult.warnings()).isEmpty();
    }

    @Test
    @DisplayName("semicolon delimiter: the FILE cell is rewritten and following cells keep their value")
    void rewrite_semicolonDelimiter_rewritesFileCellAndKeepsFollowingCells() {
        byte[] csv = bytes("testCaseName;turnIndex;document;prompt\ncase1;;files/1/report.pdf;hello there\n");
        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> columnTypes =
                Map.of("document", fileType(), "prompt", stringType());

        ZipCsvFileRefRewriter.RewriteResult result =
                rewriter.rewrite(csv, ';', columnTypes, Map.of("files/1/report.pdf", "@ef/datasets/abc/report.pdf"));

        String rewritten = new String(result.csv(), StandardCharsets.UTF_8);
        assertThat(rewritten).contains("@ef/datasets/abc/report.pdf;hello there");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("a quoted value containing a comma is rewritten when present in the archive")
    void rewrite_quotedValueWithComma_isRewritten() {
        byte[] csv = bytes("testCaseName,turnIndex,document\ncase1,,\"files/1/report, final.pdf\"\n");
        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> columnTypes = Map.of("document", fileType());

        ZipCsvFileRefRewriter.RewriteResult result = rewriter.rewrite(
                csv, ',', columnTypes, Map.of("files/1/report, final.pdf", "@ef/datasets/abc/report_final.pdf"));

        String rewritten = new String(result.csv(), StandardCharsets.UTF_8);
        assertThat(rewritten).contains("@ef/datasets/abc/report_final.pdf");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("prompt text merely containing files/... inside a longer value is left untouched")
    void rewrite_promptTextContainingPath_isLeftUntouched() {
        byte[] csv = bytes("testCaseName,turnIndex,prompt\ncase1,,Summarize files/1/doc/report.pdf please\n");
        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> columnTypes = Map.of("prompt", stringType());

        ZipCsvFileRefRewriter.RewriteResult result = rewriter.rewrite(csv, ',', columnTypes, Map.of());

        String rewritten = new String(result.csv(), StandardCharsets.UTF_8);
        assertThat(rewritten).contains("Summarize files/1/doc/report.pdf please");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("a STRING-typed column's whole-cell files/... value is left untouched")
    void rewrite_stringTypedColumn_leftUntouched() {
        byte[] csv = bytes("testCaseName,turnIndex,document\ncase1,,files/1/report.pdf\n");
        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> columnTypes = Map.of("document", stringType());

        ZipCsvFileRefRewriter.RewriteResult result =
                rewriter.rewrite(csv, ',', columnTypes, Map.of("files/1/report.pdf", "@ef/datasets/abc/report.pdf"));

        String rewritten = new String(result.csv(), StandardCharsets.UTF_8);
        assertThat(rewritten).contains("files/1/report.pdf");
        assertThat(rewritten).doesNotContain("@ef/datasets/abc/report.pdf");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("an excluded column's whole-cell files/... value is left untouched")
    void rewrite_excludedColumn_leftUntouched() {
        byte[] csv = bytes("testCaseName,turnIndex,attachment\ncase1,,files/1/report.pdf\n");
        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> columnTypes = Map.of("attachment", excluded());

        ZipCsvFileRefRewriter.RewriteResult result =
                rewriter.rewrite(csv, ',', columnTypes, Map.of("files/1/report.pdf", "@ef/datasets/abc/report.pdf"));

        String rewritten = new String(result.csv(), StandardCharsets.UTF_8);
        assertThat(rewritten).contains("files/1/report.pdf");
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("testCaseName and turnIndex columns are never rewritten even if they hold a files/... value")
    void rewrite_reservedColumns_neverRewritten() {
        byte[] csv = bytes("testCaseName,turnIndex,document\nfiles/1/weird,,files/1/report.pdf\n");
        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> columnTypes = Map.of("document", fileType());

        ZipCsvFileRefRewriter.RewriteResult result =
                rewriter.rewrite(csv, ',', columnTypes, Map.of("files/1/report.pdf", "@ef/datasets/abc/report.pdf"));

        String rewritten = new String(result.csv(), StandardCharsets.UTF_8);
        assertThat(rewritten).contains("files/1/weird");
    }

    @Test
    @DisplayName("a missing archive file becomes blank plus a warning with the correct row number and column")
    void rewrite_missingArchiveFile_blanksCellAndWarns() {
        byte[] csv = bytes("testCaseName,turnIndex,document\ncase1,,files/1/report.pdf\ncase2,,files/9/missing.pdf\n");
        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> columnTypes = Map.of("document", fileType());

        ZipCsvFileRefRewriter.RewriteResult result =
                rewriter.rewrite(csv, ',', columnTypes, Map.of("files/1/report.pdf", "@ef/datasets/abc/report.pdf"));

        assertThat(result.warnings()).hasSize(1);
        CsvImportWarningDto warning = result.warnings().getFirst();
        // header = row 1, so the second data row (case2) is row 3.
        assertThat(warning.getRowNumber()).isEqualTo(3);
        assertThat(warning.getColumnName()).isEqualTo("document");
        assertThat(warning.getMessage()).isEqualTo("File missing from archive: files/9/missing.pdf");

        String rewritten = new String(result.csv(), StandardCharsets.UTF_8);
        assertThat(rewritten.replace("\r\n", "\n")).contains("case2,,\n");
    }
}
