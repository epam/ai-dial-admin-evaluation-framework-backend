package com.epam.aidial.evaluation.service.domain.zip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.runner.client.dialcore.dto.DialFileMetadataDto;
import com.epam.aidial.evaluation.service.domain.FileService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("ZipImportUploadJournal")
@ExtendWith(MockitoExtension.class)
class ZipImportUploadJournalTest {

    @Mock
    private FileService fileService;

    private final UUID datasetId = UUID.randomUUID();

    private ZipImportUploadJournal journal;

    @BeforeEach
    void setUp() {
        journal = new ZipImportUploadJournal(datasetId, fileService);
    }

    private void stubBackupDownload(String filename, byte[] content, String contentType) {
        when(fileService.getDatasetFileMetadata(datasetId, filename))
                .thenReturn(DialFileMetadataDto.builder()
                        .name(filename)
                        .contentType(contentType)
                        .build());
        doAnswer(invocation -> {
                    OutputStream out = invocation.getArgument(2);
                    out.write(content);
                    return null;
                })
                .when(fileService)
                .downloadFromDataset(eq(datasetId), eq(filename), any());
    }

    @Test
    @DisplayName("rollback restores a backed-up file with its original content type")
    void rollback_restoresBackupWithOriginalContentType() throws IOException {
        byte[] originalContent = "original".getBytes(StandardCharsets.UTF_8);
        stubBackupDownload("report.pdf", originalContent, "application/pdf");
        // The real DialFileClient.upload reads the whole stream synchronously before returning, and
        // ZipImportUploadJournal.rollback() closes the backup temp file's stream right after that call
        // returns — so, like production, the restored bytes must be captured *during* the mocked call.
        byte[][] restoredContent = new byte[1][];
        doAnswer(invocation -> {
                    InputStream in = invocation.getArgument(2);
                    restoredContent[0] = in.readAllBytes();
                    return null;
                })
                .when(fileService)
                .putDatasetFile(eq(datasetId), eq("report.pdf"), any(), eq("application/pdf"));

        journal.backupBeforeOverwrite("report.pdf");
        journal.rollback();

        verify(fileService).putDatasetFile(eq(datasetId), eq("report.pdf"), any(), eq("application/pdf"));
        assertThat(restoredContent[0]).isEqualTo(originalContent);
    }

    @Test
    @DisplayName("rollback deletes every file the import created")
    void rollback_deletesCreatedFiles() {
        journal.recordCreated("new1.pdf");
        journal.recordCreated("new2.pdf");

        journal.rollback();

        verify(fileService).deleteByDataset(datasetId, "new1.pdf");
        verify(fileService).deleteByDataset(datasetId, "new2.pdf");
    }

    @Test
    @DisplayName("rollback continues restoring/deleting after one failure")
    void rollback_continuesAfterOneFailure() {
        stubBackupDownload("a.pdf", "content-a".getBytes(StandardCharsets.UTF_8), "application/pdf");
        stubBackupDownload("b.pdf", "content-b".getBytes(StandardCharsets.UTF_8), "application/pdf");
        journal.backupBeforeOverwrite("a.pdf");
        journal.backupBeforeOverwrite("b.pdf");
        journal.recordCreated("created.pdf");

        doThrow(new RuntimeException("boom"))
                .when(fileService)
                .putDatasetFile(eq(datasetId), eq("a.pdf"), any(), eq("application/pdf"));

        journal.rollback();

        // The failing restore (a.pdf) doesn't stop the rest: b.pdf is still restored and created.pdf deleted.
        verify(fileService, times(2)).putDatasetFile(eq(datasetId), any(), any(), eq("application/pdf"));
        verify(fileService).putDatasetFile(eq(datasetId), eq("b.pdf"), any(), eq("application/pdf"));
        verify(fileService).deleteByDataset(datasetId, "created.pdf");
    }

    @Test
    @DisplayName("rollback continues after a delete failure")
    void rollback_continuesAfterDeleteFailure() {
        journal.recordCreated("fails.pdf");
        journal.recordCreated("succeeds.pdf");
        doThrow(new RuntimeException("boom")).when(fileService).deleteByDataset(datasetId, "fails.pdf");

        journal.rollback();

        verify(fileService).deleteByDataset(datasetId, "fails.pdf");
        verify(fileService).deleteByDataset(datasetId, "succeeds.pdf");
    }

    @Test
    @DisplayName("backupBeforeOverwrite deletes the temp file when the download throws a runtime exception")
    void backupBeforeOverwrite_downloadThrowsRuntimeException_deletesTempFileAndRethrows() throws IOException {
        when(fileService.getDatasetFileMetadata(datasetId, "report.pdf"))
                .thenReturn(DialFileMetadataDto.builder()
                        .name("report.pdf")
                        .contentType("application/pdf")
                        .build());
        // A runtime exception (e.g. DialCoreClientException), not an IOException, is what
        // fileService.downloadFromDataset actually throws on a storage-side failure.
        RuntimeException downloadFailure = new RuntimeException("DIAL download failed");
        doThrow(downloadFailure).when(fileService).downloadFromDataset(eq(datasetId), eq("report.pdf"), any());

        Set<Path> before = backupTempFiles();

        assertThatThrownBy(() -> journal.backupBeforeOverwrite("report.pdf")).isSameAs(downloadFailure);

        // No "zip-import-backup-*.tmp" file is left behind by the failed backup attempt.
        assertThat(backupTempFiles()).isEqualTo(before);
    }

    private static Set<Path> backupTempFiles() throws IOException {
        Path tempDir = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("zip-import-backup-"))
                    .collect(Collectors.toSet());
        }
    }

    @Test
    @DisplayName("close deletes the backup temp files without touching dataset files")
    void close_deletesBackupTempFiles() {
        stubBackupDownload("report.pdf", "content".getBytes(StandardCharsets.UTF_8), "application/pdf");
        journal.backupBeforeOverwrite("report.pdf");

        Path tempFile = capturedBackupTempFile();
        assertThat(Files.exists(tempFile)).isTrue();

        journal.close();

        assertThat(Files.exists(tempFile)).isFalse();
        verify(fileService, never()).putDatasetFile(any(), any(), any(), any());
        verify(fileService, never()).deleteByDataset(any(), any());
    }

    /**
     * The journal doesn't expose its backup temp file path directly, so this reaches into its private state
     * via reflection purely to assert the temp file exists before {@code close()} and is gone after — the
     * journal's public contract ({@code rollback}/{@code close}) is exercised normally in every other test.
     */
    private Path capturedBackupTempFile() {
        try {
            Field backupsField = ZipImportUploadJournal.class.getDeclaredField("backups");
            backupsField.setAccessible(true);
            List<?> backups = (List<?>) backupsField.get(journal);
            Object backup = backups.getFirst();
            Field tempFileField = backup.getClass().getDeclaredField("tempFile");
            tempFileField.setAccessible(true);
            return (Path) tempFileField.get(backup);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
