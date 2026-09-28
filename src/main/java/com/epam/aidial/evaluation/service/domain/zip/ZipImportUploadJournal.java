package com.epam.aidial.evaluation.service.domain.zip;

import com.epam.aidial.evaluation.runner.client.dialcore.dto.DialFileMetadataDto;
import com.epam.aidial.evaluation.service.domain.FileService;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-request record of the DIAL writes one ZIP import makes into a dataset, so a failed import can undo
 * them (design D6/D11). Not a Spring bean: {@code ZipImportService} constructs one per request and passes it
 * the {@link FileService} it already depends on.
 *
 * <p>Every write is recorded as either <em>created</em> (a brand-new dataset file: undone by deleting it) or
 * <em>overwritten</em> (an existing file whose previous bytes and content type were first backed up to a
 * temp file via {@link #backupBeforeOverwrite}: undone by restoring that backup). Backups use {@link
 * Files#createTempFile}, not a clock — one request, one journal, no need for a timestamped name.
 *
 * <p>{@link #rollback()} is best effort: a failure to restore or delete one entry is logged (caught exception
 * as the last argument) and does not stop the rest of the rollback, per D11 ("this restoration is best
 * effort"). {@link #close()} only cleans up the temp backup files; it never touches dataset files.
 */
@Slf4j
public final class ZipImportUploadJournal implements Closeable {

    private final UUID datasetId;
    private final FileService fileService;
    private final List<String> createdFilenames = new ArrayList<>();
    private final List<Backup> backups = new ArrayList<>();

    public ZipImportUploadJournal(UUID datasetId, FileService fileService) {
        this.datasetId = datasetId;
        this.fileService = fileService;
    }

    /** Records that {@code filename} was newly created by this import (no previous content to restore). */
    public void recordCreated(String filename) {
        createdFilenames.add(filename);
    }

    /**
     * Backs up {@code filename}'s current content and content type to a temp file, before it is overwritten.
     * Must be called, and must succeed, before the overwrite is performed — design D6/D11: "if the backup
     * fails, abort here (nothing is overwritten)". The backup is later restored by {@link #rollback()} or
     * discarded by {@link #close()}.
     *
     * @throws UncheckedIOException if creating the temp file or downloading the current content fails
     */
    public void backupBeforeOverwrite(String filename) {
        DialFileMetadataDto metadata = fileService.getDatasetFileMetadata(datasetId, filename);
        String contentType = metadata != null ? metadata.getContentType() : null;

        Path tempFile;
        try {
            tempFile = Files.createTempFile("zip-import-backup-", ".tmp");
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create backup temp file for " + filename, e);
        }
        try (OutputStream out = Files.newOutputStream(tempFile)) {
            fileService.downloadFromDataset(datasetId, filename, out);
        } catch (IOException e) {
            deleteQuietly(tempFile);
            throw new UncheckedIOException("Failed to back up file before overwrite: " + filename, e);
        } catch (RuntimeException e) {
            // fileService.downloadFromDataset can throw a runtime DialCoreClientException (or similar) on a
            // storage-side failure; the temp file must not leak in that case either, only the IOException
            // path was covered before. Rethrow as-is: the caller (ZipImportService) decides how to map it.
            deleteQuietly(tempFile);
            throw e;
        }
        backups.add(new Backup(filename, tempFile, contentType));
    }

    /**
     * Restores every backed-up file to its previous content and content type, and deletes every newly
     * created file, best effort: one failure is logged and does not stop the rest.
     */
    public void rollback() {
        for (Backup backup : backups) {
            try (InputStream in = Files.newInputStream(backup.tempFile())) {
                fileService.putDatasetFile(datasetId, backup.filename(), in, backup.contentType());
            } catch (IOException | RuntimeException e) {
                log.warn(
                        "Failed to restore backed-up file {} for dataset {}: {}",
                        backup.filename(),
                        datasetId,
                        e.getMessage(),
                        e);
            }
        }
        for (String filename : createdFilenames) {
            try {
                fileService.deleteByDataset(datasetId, filename);
            } catch (RuntimeException e) {
                log.warn("Failed to delete created file {} for dataset {}: {}", filename, datasetId, e.getMessage(), e);
            }
        }
    }

    /** Deletes every backup temp file. Never restores or deletes dataset files. */
    @Override
    public void close() {
        for (Backup backup : backups) {
            deleteQuietly(backup.tempFile());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temp backup file {}: {}", path, e.getMessage(), e);
        }
    }

    private record Backup(String filename, Path tempFile, String contentType) {}
}
