package com.epam.aidial.evaluation.service.domain.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.configuration.properties.csv.CsvImportProperties;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import org.springframework.util.unit.DataUnit;

@DisplayName("ZipArchiveReader")
class ZipArchiveReaderTest {

    private static final byte[] CSV_BYTES =
            "testCaseName,turnIndex,document\nrow1,,files/1/report.pdf\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] MANIFEST_BYTES =
            "{\"formatVersion\":1,\"testCaseSchema\":[]}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FILE_BYTES = "report content".getBytes(StandardCharsets.UTF_8);

    @TempDir
    private Path tempDir;

    private ZipArchiveReader reader(
            int maxEntries, long maxTotalUncompressedBytes, long maxCsvBytes, long maxFileBytes) {
        CsvImportProperties csvImportProperties = new CsvImportProperties();
        csvImportProperties.setMaxFileSize(DataSize.of(maxCsvBytes, DataUnit.BYTES));
        csvImportProperties.setMaxRows(10_000);
        csvImportProperties.setBatchSize(100);
        CsvImportProperties.Zip zip = new CsvImportProperties.Zip();
        zip.setMaxEntries(maxEntries);
        zip.setMaxTotalUncompressedSize(DataSize.of(maxTotalUncompressedBytes, DataUnit.BYTES));
        csvImportProperties.setZip(zip);

        DialFileStorageProperties fileStorageProperties = new DialFileStorageProperties();
        fileStorageProperties.setMaxFileSizeBytes(maxFileBytes);
        fileStorageProperties.setMaxFilesPerDataset(100);
        fileStorageProperties.setMaxFilesPerSuite(100);
        fileStorageProperties.setBucketAlias("@ef");

        return new ZipArchiveReader(csvImportProperties, fileStorageProperties);
    }

    private ZipArchiveReader defaultReader() {
        return reader(1000, 1_000_000, 1_000_000, 1_000_000);
    }

    private Path zipOf(Map<String, byte[]> entries) throws IOException {
        return writeZip(zipBytes(entries));
    }

    private static byte[] zipBytes(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private Path writeZip(byte[] bytes) throws IOException {
        Path path = tempDir.resolve("archive-" + UUID.randomUUID() + ".zip");
        Files.write(path, bytes);
        return path;
    }

    private static Map<String, byte[]> baseEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("test-cases.csv", CSV_BYTES);
        entries.put("manifest.json", MANIFEST_BYTES);
        entries.put("files/1/report.pdf", FILE_BYTES);
        return entries;
    }

    @Test
    @DisplayName("opens a well-formed archive and exposes CSV, manifest and file paths")
    void open_validArchive_exposesCsvManifestAndFilePaths() throws IOException {
        Path archive = zipOf(baseEntries());

        try (ZipArchiveReader.OpenZip openZip = defaultReader().open(archive)) {
            assertThat(openZip.csvBytes()).isEqualTo(CSV_BYTES);
            assertThat(openZip.manifestBytes()).contains(MANIFEST_BYTES);
            assertThat(openZip.filePaths()).containsExactly("files/1/report.pdf");
            assertThat(openZip.openFile("files/1/report.pdf").readAllBytes()).isEqualTo(FILE_BYTES);
        }
    }

    @Test
    @DisplayName("strips a single top-level folder")
    void open_nestedUnderTopLevelFolder_stripsToRoot() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("my-dataset/test-cases.csv", CSV_BYTES);
        entries.put("my-dataset/files/1/a.pdf", FILE_BYTES);
        Path archive = zipOf(entries);

        try (ZipArchiveReader.OpenZip openZip = defaultReader().open(archive)) {
            assertThat(openZip.csvBytes()).isEqualTo(CSV_BYTES);
            assertThat(openZip.filePaths()).containsExactly("files/1/a.pdf");
        }
    }

    @Test
    @DisplayName("ignores __MACOSX entries")
    void open_ignoresMacosxEntries() throws IOException {
        Map<String, byte[]> entries = baseEntries();
        entries.put("__MACOSX/._test-cases.csv", new byte[] {1, 2, 3});
        Path archive = zipOf(entries);

        try (ZipArchiveReader.OpenZip openZip = defaultReader().open(archive)) {
            assertThat(openZip.csvBytes()).isEqualTo(CSV_BYTES);
            assertThat(openZip.filePaths()).containsExactly("files/1/report.pdf");
        }
    }

    @Test
    @DisplayName("corrupt / non-ZIP content is rejected with 400, not an unmapped 500")
    void open_corruptContent_throwsValidationException() throws IOException {
        // Garbage bytes named ".zip": ZipFile's constructor throws java.util.zip.ZipException (a checked
        // IOException subtype) for this, which must not surface as an unmapped UncheckedIOException.
        Path archive = tempDir.resolve("garbage.zip");
        Files.write(
                archive, "this is not a zip file at all, just plain garbage bytes".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> defaultReader().open(archive))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid ZIP archive");
    }

    @Test
    @DisplayName("missing test-cases.csv is rejected")
    void open_missingCsv_throwsValidationException() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", MANIFEST_BYTES);
        Path archive = zipOf(entries);

        assertThatThrownBy(() -> defaultReader().open(archive))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("test-cases.csv");
    }

    @Test
    @DisplayName("too many entries is rejected")
    void open_tooManyEntries_throwsValidationException() throws IOException {
        Path archive = zipOf(baseEntries());

        assertThatThrownBy(() -> reader(2, 1_000_000, 1_000_000, 1_000_000).open(archive))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("too many entries");
    }

    @Test
    @DisplayName("total uncompressed size above the limit is rejected")
    void open_totalUncompressedSizeExceedsLimit_throwsValidationException() throws IOException {
        Path archive = zipOf(baseEntries());

        assertThatThrownBy(() -> reader(1000, 10, 1_000_000, 1_000_000).open(archive))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("total uncompressed size");
    }

    @Test
    @DisplayName("CSV entry above csv.import.max-file-size is rejected")
    void open_csvExceedsMaxFileSize_throwsValidationException() throws IOException {
        Path archive = zipOf(baseEntries());

        assertThatThrownBy(() -> reader(1000, 1_000_000, 5, 1_000_000).open(archive))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("test-cases.csv size exceeds maximum");
    }

    @Test
    @DisplayName("path traversal entry is rejected")
    void open_pathTraversalEntry_throwsValidationException() throws IOException {
        Map<String, byte[]> entries = baseEntries();
        entries.put("files/../../etc/passwd", new byte[] {1});
        Path archive = zipOf(entries);

        assertThatThrownBy(() -> defaultReader().open(archive))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid ZIP entry path");
    }

    @Test
    @DisplayName("absolute path entry is rejected")
    void open_absolutePathEntry_throwsValidationException() throws IOException {
        Map<String, byte[]> entries = baseEntries();
        entries.put("/etc/passwd", new byte[] {1});
        Path archive = zipOf(entries);

        assertThatThrownBy(() -> defaultReader().open(archive))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid ZIP entry path");
    }

    @Test
    @DisplayName("duplicate normalized entry names are rejected")
    void open_duplicateNormalizedNames_throwsValidationException() throws IOException {
        // ZipOutputStream itself refuses two entries with one literal name, so build two distinctly named,
        // same-length entries and rename the second's local-header/central-directory name field in place to
        // match the first — reproducing a hand-crafted archive with a genuine duplicate path.
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("test-cases.csv", CSV_BYTES);
        entries.put("files/1/aaa.pdf", FILE_BYTES);
        entries.put("files/1/bbb.pdf", FILE_BYTES);
        byte[] zip = renameEntry(zipBytes(entries), "files/1/bbb.pdf", "files/1/aaa.pdf");
        Path archive = writeZip(zip);

        assertThatThrownBy(() -> defaultReader().open(archive))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Duplicate ZIP entry path");
    }

    /** Renames every occurrence of a ZIP entry's exact name bytes; {@code from} and {@code to} must be equal length. */
    private static byte[] renameEntry(byte[] zip, String from, String to) {
        byte[] fromBytes = from.getBytes(StandardCharsets.UTF_8);
        byte[] toBytes = to.getBytes(StandardCharsets.UTF_8);
        if (fromBytes.length != toBytes.length) {
            throw new IllegalArgumentException("Names must be the same length to rename in place");
        }
        byte[] patched = zip.clone();
        outer:
        for (int i = 0; i + fromBytes.length <= patched.length; i++) {
            for (int j = 0; j < fromBytes.length; j++) {
                if (patched[i + j] != fromBytes[j]) {
                    continue outer;
                }
            }
            System.arraycopy(toBytes, 0, patched, i, toBytes.length);
        }
        return patched;
    }

    @Test
    @DisplayName("a file entry's declared size above dial.file-storage.max-file-size-bytes is rejected")
    void openFile_declaredSizeExceedsMaxFileSizeBytes_throwsValidationException() throws IOException {
        Path archive = zipOf(baseEntries());

        try (ZipArchiveReader.OpenZip openZip =
                reader(1000, 1_000_000, 1_000_000, 3).open(archive)) {
            assertThatThrownBy(() -> openZip.openFile("files/1/report.pdf"))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("exceeds maximum");
        }
    }

    @Test
    @DisplayName("validateReferencedEntries rejects an oversize declared size before any file is opened/read")
    void validateReferencedEntries_declaredSizeExceedsCap_throwsBeforeAnyRead() throws IOException {
        Path archive = zipOf(baseEntries());

        // FILE_BYTES is 14 bytes; a per-file cap of 3 makes the entry's declared (central-directory) size
        // already exceed it, so validateReferencedEntries must reject it up front — before the upload loop
        // would otherwise open/read it and, on a later entry's failure, have to roll back this one.
        try (ZipArchiveReader.OpenZip openZip =
                reader(1000, 1_000_000, 1_000_000, 3).open(archive)) {
            assertThatThrownBy(() -> openZip.validateReferencedEntries(Set.of("files/1/report.pdf")))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("exceeds maximum");
        }
    }

    @Test
    @DisplayName("validateReferencedEntries ignores a referenced path absent from the archive")
    void validateReferencedEntries_pathAbsentFromArchive_isIgnored() throws IOException {
        Path archive = zipOf(baseEntries());

        try (ZipArchiveReader.OpenZip openZip =
                reader(1000, 1_000_000, 1_000_000, 3).open(archive)) {
            // "files/9/missing.pdf" isn't in the archive at all: not this method's concern (the rewriter
            // reports it as a missing-file warning instead), so it must not throw here.
            openZip.validateReferencedEntries(Set.of("files/9/missing.pdf"));
        }
    }

    @Test
    @DisplayName(
            "a running total across CSV and multiple file reads is enforced even though each stays under its own cap")
    void openFile_runningTotalAcrossEntries_isEnforced() throws IOException {
        byte[] file1Content = new byte[60];
        Arrays.fill(file1Content, (byte) 'a');
        byte[] file2Content = new byte[60];
        Arrays.fill(file2Content, (byte) 'b');

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("test-cases.csv", CSV_BYTES);
        entries.put("files/1/a.bin", file1Content);
        entries.put("files/2/b.bin", file2Content);
        byte[] zip = zipBytes(entries);
        // Lie about both files' declared sizes so the eager (header-sum) total check at open() time passes,
        // even though their real bytes will exceed the running total once they are actually read.
        zip = lieAboutUncompressedSize(zip, "files/1/a.bin", 5);
        zip = lieAboutUncompressedSize(zip, "files/2/b.bin", 5);
        Path archive = writeZip(zip);

        // Enough for the CSV (read at open time) plus file1, but not for file1 and file2 together.
        long totalCap = CSV_BYTES.length + file1Content.length + 10;
        try (ZipArchiveReader.OpenZip openZip =
                reader(1000, totalCap, 1_000_000, 1000).open(archive)) {
            assertThat(openZip.openFile("files/1/a.bin").readAllBytes()).isEqualTo(file1Content);

            // file2 alone is well under its own per-file cap (1000) and its declared size (patched to 5) is
            // tiny, but reading it pushes the shared running total over totalCap.
            assertThatThrownBy(() -> openZip.openFile("files/2/b.bin").readAllBytes())
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("total uncompressed size");
        }
    }

    @Test
    @DisplayName("an entry whose header understates its size fails while its bytes are actually read")
    void openFile_entryHeaderUnderstatesSize_failsWhileReading() throws IOException {
        byte[] realBigContent = new byte[5000];
        Arrays.fill(realBigContent, (byte) 'a');
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("test-cases.csv", CSV_BYTES);
        entries.put("files/1/big.bin", realBigContent);
        byte[] zip = zipBytes(entries);
        byte[] lyingZip = lieAboutUncompressedSize(zip, "files/1/big.bin", 10);
        Path archive = writeZip(lyingZip);

        // Cap large enough to pass the header pre-check (10 <= cap) but smaller than the real content (5000).
        try (ZipArchiveReader.OpenZip openZip =
                reader(1000, 1_000_000, 1_000_000, 100).open(archive)) {
            assertThatThrownBy(() -> openZip.openFile("files/1/big.bin").readAllBytes())
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("exceeds maximum");
        }
    }

    /**
     * Patches the ZIP central directory's declared uncompressed size for {@code entryName} to {@code
     * declaredSize}, without touching the real (larger) compressed data — reproducing an entry whose header
     * lies about its size, which {@link ZipFile#getInputStream} still fully decompresses regardless of the
     * declared size.
     */
    private static byte[] lieAboutUncompressedSize(byte[] zip, String entryName, int declaredSize) {
        byte[] nameBytes = entryName.getBytes(StandardCharsets.UTF_8);
        byte[] patched = zip.clone();
        for (int i = 0; i + 4 <= patched.length; i++) {
            // Central directory file header signature: PK\x01\x02
            if ((patched[i] & 0xff) == 0x50
                    && (patched[i + 1] & 0xff) == 0x4b
                    && (patched[i + 2] & 0xff) == 0x01
                    && (patched[i + 3] & 0xff) == 0x02) {
                int nameLen = readLe16(patched, i + 28);
                if (nameLen == nameBytes.length && matches(patched, i + 46, nameBytes)) {
                    writeLe32(patched, i + 24, declaredSize);
                    return patched;
                }
            }
        }
        throw new IllegalStateException("Central directory entry not found: " + entryName);
    }

    private static boolean matches(byte[] data, int offset, byte[] expected) {
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int readLe16(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8);
    }

    private static void writeLe32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xff);
        b[off + 1] = (byte) ((v >> 8) & 0xff);
        b[off + 2] = (byte) ((v >> 16) & 0xff);
        b[off + 3] = (byte) ((v >> 24) & 0xff);
    }
}
