package com.epam.aidial.evaluation.service.domain.zip;

import com.epam.aidial.evaluation.configuration.properties.csv.CsvImportProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.io.LimitingInputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Opens a staged ZIP archive as a {@link ZipFile} (random-access, so entry sizes come from the trustworthy
 * central directory rather than a streamed local-file header) and normalizes and validates its entries per
 * design D6/D11:
 *
 * <ul>
 *   <li>strips a single top-level folder, so entries nested under one directory import as if they were at
 *       the archive root;
 *   <li>ignores directory entries and anything under {@code __MACOSX/}, macOS's zip metadata folder;
 *   <li>rejects, before any write, an absolute path, a path containing a {@code ..} segment or a backslash,
 *       a duplicate normalized name, more entries than {@code csv.import.zip.max-entries}, a total declared
 *       (uncompressed) size above {@code csv.import.zip.max-total-uncompressed-size}, a missing {@code
 *       test-cases.csv}, and either required entry's declared size over its own cap;
 *   <li>wraps every entry's actual read (CSV, manifest, and later, on demand, each {@code files/…} entry) in
 *       a {@link LimitingInputStream} enforcing the same per-entry caps on the bytes actually read, so an
 *       entry whose header understates its real size still fails instead of exhausting memory, <em>and</em>
 *       accumulates every one of those reads into one running total per {@link OpenZip}, checked against
 *       {@code csv.import.zip.max-total-uncompressed-size} (design D6 stage 2: "the same caps and a running
 *       total") — so entries whose headers each individually lie just enough to pass their own cap still
 *       can't add up to an unbounded real read.
 * </ul>
 *
 * <p>The CSV and manifest are read fully at open time (design D6 steps 1–2); {@code files/…} entries are
 * exposed only as lazily opened streams via {@link OpenZip#openFile(String)}, opened later, only for entries
 * the CSV actually references (design step 7), so an unreferenced entry — however large — is never read.
 * {@link OpenZip#validateReferencedEntries(Set)} lets the caller reject an oversize referenced entry's
 * declared size <em>before</em> any upload starts (design D6 stage 1 / spec "ZIP import archive limits and
 * entry validation"): checking only inside {@link OpenZip#openFile(String)} would mean an oversize 3rd
 * referenced entry surfaces only after the first two are already uploaded, forcing a rollback instead of a
 * clean 400 with nothing written. {@code openFile} keeps its own declared-size check too, as defense against
 * a caller that skips {@code validateReferencedEntries}.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class ZipArchiveReader {

    static final String CSV_ENTRY_NAME = "test-cases.csv";
    static final String MANIFEST_ENTRY_NAME = "manifest.json";
    static final String FILES_PREFIX = "files/";
    private static final String MACOSX_PREFIX = "__MACOSX/";
    private static final String MACOSX_ENTRY = "__MACOSX";

    private final CsvImportProperties csvImportProperties;
    private final DialFileStorageProperties fileStorageProperties;

    /**
     * Opens {@code stagedFile} and eagerly validates and reads everything except {@code files/…} entry
     * bytes. Throws {@link ValidationException} (mapped to HTTP 400) for any structural or limit violation,
     * including corrupt or non-ZIP content: {@link ZipFile}'s constructor throws {@link ZipException} (a
     * checked {@link IOException} subtype) for that case, which would otherwise surface as an unmapped
     * {@link java.io.UncheckedIOException} (HTTP 500) once the caller wraps the declared {@code IOException}.
     * The returned {@link OpenZip} must be closed by the caller to release the underlying {@link ZipFile}.
     */
    public OpenZip open(Path stagedFile) throws IOException {
        ZipFile zipFile;
        try {
            zipFile = new ZipFile(stagedFile.toFile());
        } catch (ZipException e) {
            throw new ValidationException("Invalid ZIP archive: " + e.getMessage());
        }
        boolean success = false;
        try {
            OpenZip result = doOpen(zipFile);
            success = true;
            return result;
        } finally {
            if (!success) {
                closeQuietly(zipFile);
            }
        }
    }

    private OpenZip doOpen(ZipFile zipFile) throws IOException {
        List<? extends ZipEntry> entries = zipFile.stream().toList();

        int maxEntries = csvImportProperties.getZip().getMaxEntries();
        if (entries.size() > maxEntries) {
            throw new ValidationException("ZIP archive contains too many entries (max " + maxEntries + ")");
        }

        long totalDeclaredSize = 0;
        for (ZipEntry entry : entries) {
            validateEntryPath(entry.getName());
            totalDeclaredSize += Math.max(entry.getSize(), 0);
        }
        long maxTotalSize =
                csvImportProperties.getZip().getMaxTotalUncompressedSize().toBytes();
        if (totalDeclaredSize > maxTotalSize) {
            throw new ValidationException(
                    "ZIP archive total uncompressed size exceeds maximum of " + maxTotalSize + " bytes");
        }

        List<ZipEntry> normalEntries = new ArrayList<>();
        for (ZipEntry entry : entries) {
            if (entry.isDirectory() || isMacosxEntry(entry.getName())) {
                continue;
            }
            normalEntries.add(entry);
        }

        String topLevelFolder = detectTopLevelFolder(normalEntries);
        Map<String, ZipEntry> normalizedEntries = new LinkedHashMap<>();
        for (ZipEntry entry : normalEntries) {
            String normalized =
                    topLevelFolder != null ? entry.getName().substring(topLevelFolder.length()) : entry.getName();
            if (normalizedEntries.putIfAbsent(normalized, entry) != null) {
                throw new ValidationException("Duplicate ZIP entry path: " + normalized);
            }
        }

        // Shared across the CSV/manifest reads below and every later OpenZip.openFile() read: design D6
        // stage 2's "running total", counting every entry's actually-read bytes against the archive-wide
        // cap regardless of what any individual entry's header declared.
        RunningTotal runningTotal = new RunningTotal(maxTotalSize);

        ZipEntry csvEntry = normalizedEntries.get(CSV_ENTRY_NAME);
        if (csvEntry == null) {
            throw new ValidationException("ZIP archive must contain a test-cases.csv file");
        }
        byte[] csvBytes = readCapped(
                zipFile,
                csvEntry,
                csvImportProperties.getMaxFileSize().toBytes(),
                max -> new ValidationException("test-cases.csv size exceeds maximum of " + max + " bytes"),
                runningTotal);

        ZipEntry manifestEntry = normalizedEntries.get(MANIFEST_ENTRY_NAME);
        byte[] manifestBytes = manifestEntry == null
                ? null
                : readCapped(
                        zipFile,
                        manifestEntry,
                        maxTotalSize,
                        max -> new ValidationException("manifest.json size exceeds maximum of " + max + " bytes"),
                        runningTotal);

        Map<String, ZipEntry> fileEntries = new LinkedHashMap<>();
        for (Map.Entry<String, ZipEntry> entry : normalizedEntries.entrySet()) {
            if (entry.getKey().startsWith(FILES_PREFIX)) {
                fileEntries.put(entry.getKey(), entry.getValue());
            }
        }

        return new OpenZip(
                zipFile,
                csvBytes,
                Optional.ofNullable(manifestBytes),
                fileEntries,
                fileStorageProperties,
                runningTotal);
    }

    private static boolean isMacosxEntry(String name) {
        return name.equals(MACOSX_ENTRY) || name.startsWith(MACOSX_PREFIX);
    }

    private static void validateEntryPath(String rawName) {
        if (rawName.startsWith("/")) {
            throw new ValidationException("Invalid ZIP entry path: " + rawName);
        }
        if (rawName.contains("\\")) {
            throw new ValidationException("Invalid ZIP entry path: " + rawName);
        }
        for (String segment : rawName.split("/")) {
            if (segment.equals("..")) {
                throw new ValidationException("Invalid ZIP entry path: " + rawName);
            }
        }
    }

    /**
     * Returns the shared prefix (including the trailing {@code /}) that every entry in {@code entries}
     * starts with, provided every entry has at least one {@code /} in its name; {@code null} if the entries
     * are already at the archive root (any entry with no {@code /}) or don't share one folder.
     */
    private static String detectTopLevelFolder(List<ZipEntry> entries) {
        String candidate = null;
        for (ZipEntry entry : entries) {
            String name = entry.getName();
            int slash = name.indexOf('/');
            if (slash <= 0) {
                return null;
            }
            String prefix = name.substring(0, slash + 1);
            if (candidate == null) {
                candidate = prefix;
            } else if (!candidate.equals(prefix)) {
                return null;
            }
        }
        return candidate;
    }

    private static byte[] readCapped(
            ZipFile zipFile,
            ZipEntry entry,
            long cap,
            LongFunction<? extends RuntimeException> exceptionFactory,
            RunningTotal runningTotal) {
        long declaredSize = entry.getSize();
        if (declaredSize >= 0 && declaredSize > cap) {
            throw exceptionFactory.apply(cap);
        }
        try (InputStream raw = zipFile.getInputStream(entry);
                InputStream limited = new LimitingInputStream(raw, cap, exceptionFactory);
                InputStream counted = runningTotal.wrap(limited)) {
            return counted.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read ZIP entry: " + entry.getName(), e);
        }
    }

    private static void closeQuietly(ZipFile zipFile) {
        try {
            zipFile.close();
        } catch (IOException e) {
            log.warn("Failed to close ZIP archive: {}", e.getMessage(), e);
        }
    }

    /**
     * One archive's running total of actually-read bytes across every entry (CSV, manifest, and each {@code
     * files/…} entry {@link OpenZip#openFile} later opens), checked against {@code
     * csv.import.zip.max-total-uncompressed-size} regardless of any individual entry's own cap or declared
     * size (design D6 stage 2). Not thread-safe; one {@link OpenZip} (one import request) owns one instance.
     */
    private static final class RunningTotal {

        private final long max;
        private long total;

        RunningTotal(long max) {
            this.max = max;
        }

        InputStream wrap(InputStream in) {
            return new FilterInputStream(in) {
                @Override
                public int read() throws IOException {
                    int b = super.read();
                    if (b != -1) {
                        add(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len);
                    if (n > 0) {
                        add(n);
                    }
                    return n;
                }
            };
        }

        private void add(long n) {
            total += n;
            if (total > max) {
                throw new ValidationException(
                        "ZIP archive total uncompressed size exceeds maximum of " + max + " bytes");
            }
        }
    }

    /**
     * An opened, validated archive. Exposes the CSV bytes, the optional manifest bytes, the set of {@code
     * files/…} entry paths present, and lazy access to each file entry's bytes. Must be closed to release
     * the underlying {@link ZipFile}.
     */
    @Slf4j
    public static final class OpenZip implements Closeable {

        private final ZipFile zipFile;
        private final byte[] csvBytes;
        private final Optional<byte[]> manifestBytes;
        private final Map<String, ZipEntry> fileEntries;
        private final DialFileStorageProperties fileStorageProperties;
        private final RunningTotal runningTotal;

        private OpenZip(
                ZipFile zipFile,
                byte[] csvBytes,
                Optional<byte[]> manifestBytes,
                Map<String, ZipEntry> fileEntries,
                DialFileStorageProperties fileStorageProperties,
                RunningTotal runningTotal) {
            this.zipFile = zipFile;
            this.csvBytes = csvBytes;
            this.manifestBytes = manifestBytes;
            this.fileEntries = fileEntries;
            this.fileStorageProperties = fileStorageProperties;
            this.runningTotal = runningTotal;
        }

        public byte[] csvBytes() {
            return csvBytes;
        }

        public Optional<byte[]> manifestBytes() {
            return manifestBytes;
        }

        /** Normalized {@code files/…} paths present in the archive (whether or not the CSV references them). */
        public Set<String> filePaths() {
            return new LinkedHashSet<>(fileEntries.keySet());
        }

        /**
         * Checks each of {@code referencedPaths} that is present in the archive against {@code
         * dial.file-storage.max-file-size-bytes}, using the entry's declared (central-directory) size —
         * design D6 stage 1 / spec "ZIP import archive limits and entry validation": a referenced entry
         * whose header already exceeds the cap must be rejected with HTTP 400 <em>before any file is
         * written</em>. Checking only inside {@link #openFile(String)} would surface this only when that
         * entry's turn in the upload loop (design step 7) comes up, after earlier entries were already
         * uploaded — turning a clean 400 into a needless rollback. A referenced path absent from the
         * archive is not this method's concern; the rewriter reports that as a missing-file warning.
         *
         * @throws ValidationException if any present, referenced entry's declared size exceeds the cap
         */
        public void validateReferencedEntries(Set<String> referencedPaths) {
            long cap = fileStorageProperties.getMaxFileSizeBytes();
            for (String path : referencedPaths) {
                ZipEntry entry = fileEntries.get(path);
                if (entry == null) {
                    continue;
                }
                long declaredSize = entry.getSize();
                if (declaredSize >= 0 && declaredSize > cap) {
                    throw new ValidationException(
                            "Archive file '" + path + "' size exceeds maximum of " + cap + " bytes");
                }
            }
        }

        /**
         * Lazily opens the given {@code files/…} entry's bytes, checking its declared size against {@code
         * dial.file-storage.max-file-size-bytes} up front (defense in addition to {@link
         * #validateReferencedEntries}, which the caller is expected to have already run for every
         * referenced path before uploading any of them) and wrapping the actual read in the same cap, so an
         * entry whose header understates its real (decompressed) size still fails as it is read. The read
         * also counts toward this archive's shared running total against {@code
         * csv.import.zip.max-total-uncompressed-size} (design D6 stage 2).
         *
         * @throws IllegalArgumentException if {@code path} is not one of {@link #filePaths()}
         * @throws ValidationException if the entry's declared or actual size exceeds its own cap, or the
         *     archive's running total exceeds {@code csv.import.zip.max-total-uncompressed-size}
         */
        public InputStream openFile(String path) {
            ZipEntry entry = fileEntries.get(path);
            if (entry == null) {
                throw new IllegalArgumentException("Unknown archive file path: " + path);
            }
            long cap = fileStorageProperties.getMaxFileSizeBytes();
            long declaredSize = entry.getSize();
            if (declaredSize >= 0 && declaredSize > cap) {
                throw new ValidationException("Archive file '" + path + "' size exceeds maximum of " + cap + " bytes");
            }
            try {
                InputStream raw = zipFile.getInputStream(entry);
                InputStream limited = new LimitingInputStream(
                        raw,
                        cap,
                        max -> new ValidationException(
                                "Archive file '" + path + "' size exceeds maximum of " + max + " bytes"));
                return runningTotal.wrap(limited);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to open archive entry: " + path, e);
            }
        }

        @Override
        public void close() {
            try {
                zipFile.close();
            } catch (IOException e) {
                log.warn("Failed to close ZIP archive: {}", e.getMessage(), e);
            }
        }
    }
}
