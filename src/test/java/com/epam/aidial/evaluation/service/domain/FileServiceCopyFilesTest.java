package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.client.dialcore.dto.DialFileMetadataDto;
import com.epam.aidial.evaluation.configuration.properties.dial.DialFileStorageProperties;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatusCode;

@DisplayName("FileService — copyFilesBetweenSuites")
@ExtendWith(MockitoExtension.class)
class FileServiceCopyFilesTest {

    @Mock
    private DialFileClient dialFileClient;

    @Mock
    private DialFileRefResolver dialFileRefResolver;

    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private DatasetRepository datasetRepository;

    @Mock
    private DialFileStorageProperties fileStorageProperties;

    private FileService fileService;
    private final UUID sourceId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();
    private final String bucket = "test-bucket";

    @BeforeEach
    void setUp() {
        when(dialFileClient.getBucket()).thenReturn(bucket);
        fileService = new FileService(
                dialFileClient, dialFileRefResolver, testSuiteRepository, datasetRepository, fileStorageProperties);
    }

    @Test
    @DisplayName("copies all files from source to target and returns their filenames")
    void copyFilesBetweenSuites_copiesAllFiles_whenSourceHasFiles() {
        String sourcePath = bucket + "/suites/" + sourceId + "/";
        DialFileMetadataDto file1 = DialFileMetadataDto.builder()
                .name("data.csv")
                .contentType("text/csv")
                .build();
        DialFileMetadataDto file2 = DialFileMetadataDto.builder()
                .name("config.json")
                .contentType("application/json")
                .build();
        when(dialFileClient.list(sourcePath)).thenReturn(List.of(file1, file2));
        when(dialFileClient.download(sourcePath + "data.csv")).thenReturn(new byte[] {1, 2, 3});
        when(dialFileClient.download(sourcePath + "config.json")).thenReturn(new byte[] {4, 5});

        List<String> copied = fileService.copyFilesBetweenSuites(sourceId, targetId);

        assertThat(copied).containsExactlyInAnyOrder("data.csv", "config.json");
        String targetPath = bucket + "/suites/" + targetId + "/";
        verify(dialFileClient).upload(eq(targetPath + "data.csv"), any(), eq("data.csv"), eq("text/csv"));
        verify(dialFileClient).upload(eq(targetPath + "config.json"), any(), eq("config.json"), eq("application/json"));
    }

    @Test
    @DisplayName("skips file and continues when download fails, still copies successful files")
    void copyFilesBetweenSuites_skipsFile_whenDownloadFails() {
        String sourcePath = bucket + "/suites/" + sourceId + "/";
        DialFileMetadataDto goodFile = DialFileMetadataDto.builder()
                .name("good.txt")
                .contentType("text/plain")
                .build();
        DialFileMetadataDto badFile = DialFileMetadataDto.builder()
                .name("missing.txt")
                .contentType("text/plain")
                .build();
        when(dialFileClient.list(sourcePath)).thenReturn(List.of(goodFile, badFile));
        when(dialFileClient.download(sourcePath + "good.txt")).thenReturn(new byte[] {7, 8});
        when(dialFileClient.download(sourcePath + "missing.txt"))
                .thenThrow(new DialCoreClientException(HttpStatusCode.valueOf(404), "Not found", "File not found"));

        List<String> copied = fileService.copyFilesBetweenSuites(sourceId, targetId);

        assertThat(copied).containsExactly("good.txt");
        String targetPath = bucket + "/suites/" + targetId + "/";
        verify(dialFileClient).upload(eq(targetPath + "good.txt"), any(), eq("good.txt"), eq("text/plain"));
        verify(dialFileClient, never()).upload(eq(targetPath + "missing.txt"), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("returns empty list when source folder has no files")
    void copyFilesBetweenSuites_returnsEmptyList_whenSourceFolderEmpty() {
        String sourcePath = bucket + "/suites/" + sourceId + "/";
        when(dialFileClient.list(sourcePath)).thenReturn(List.of());

        List<String> copied = fileService.copyFilesBetweenSuites(sourceId, targetId);

        assertThat(copied).isEmpty();
        verify(dialFileClient, never()).download(anyString());
        verify(dialFileClient, never()).upload(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("returns empty list and does not throw when listing source folder fails")
    void copyFilesBetweenSuites_returnsEmptyList_whenListFails() {
        String sourcePath = bucket + "/suites/" + sourceId + "/";
        when(dialFileClient.list(sourcePath))
                .thenThrow(new DialCoreClientException(HttpStatusCode.valueOf(500), "Error", "Server error"));

        List<String> copied = fileService.copyFilesBetweenSuites(sourceId, targetId);

        assertThat(copied).isEmpty();
        verify(dialFileClient, never()).download(anyString());
        verify(dialFileClient, never()).upload(anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("uses application/octet-stream content type when file metadata has null contentType")
    void copyFilesBetweenSuites_usesDefaultContentType_whenFileMetadataHasNullContentType() {
        String sourcePath = bucket + "/suites/" + sourceId + "/";
        DialFileMetadataDto file = DialFileMetadataDto.builder()
                .name("binary.bin")
                .contentType(null)
                .build();
        when(dialFileClient.list(sourcePath)).thenReturn(List.of(file));
        when(dialFileClient.download(sourcePath + "binary.bin")).thenReturn(new byte[] {});

        fileService.copyFilesBetweenSuites(sourceId, targetId);

        String targetPath = bucket + "/suites/" + targetId + "/";
        verify(dialFileClient)
                .upload(eq(targetPath + "binary.bin"), any(), eq("binary.bin"), eq("application/octet-stream"));
    }

    @Test
    @DisplayName("does not validate target suite in DB — copies files even before target exists")
    void copyFilesBetweenSuites_doesNotValidateTargetSuiteInDb() {
        String sourcePath = bucket + "/suites/" + sourceId + "/";
        when(dialFileClient.list(sourcePath)).thenReturn(List.of());

        fileService.copyFilesBetweenSuites(sourceId, targetId);

        // testSuiteRepository.existsById should never be called for targetId validation
        verify(testSuiteRepository, never()).existsById(targetId);
    }

    @Test
    @DisplayName("copyFilesBetweenDatasets copies all files from source dataset folder to target dataset folder")
    void copyFilesBetweenDatasets_copiesAllFiles_whenSourceHasFiles() {
        String sourcePath = bucket + "/datasets/" + sourceId + "/";
        DialFileMetadataDto file1 = DialFileMetadataDto.builder()
                .name("data.csv")
                .contentType("text/csv")
                .build();
        DialFileMetadataDto file2 = DialFileMetadataDto.builder()
                .name("config.json")
                .contentType("application/json")
                .build();
        when(dialFileClient.list(sourcePath)).thenReturn(List.of(file1, file2));
        when(dialFileClient.download(sourcePath + "data.csv")).thenReturn(new byte[] {1, 2, 3});
        when(dialFileClient.download(sourcePath + "config.json")).thenReturn(new byte[] {4, 5});

        List<String> copied = fileService.copyFilesBetweenDatasets(sourceId, targetId);

        assertThat(copied).containsExactlyInAnyOrder("data.csv", "config.json");
        String targetPath = bucket + "/datasets/" + targetId + "/";
        verify(dialFileClient).upload(eq(targetPath + "data.csv"), any(), eq("data.csv"), eq("text/csv"));
        verify(dialFileClient).upload(eq(targetPath + "config.json"), any(), eq("config.json"), eq("application/json"));
    }

    @Test
    @DisplayName("copyFilesBetweenDatasets skips a file and continues when its download fails")
    void copyFilesBetweenDatasets_skipsFile_whenDownloadFails() {
        String sourcePath = bucket + "/datasets/" + sourceId + "/";
        DialFileMetadataDto goodFile = DialFileMetadataDto.builder()
                .name("good.txt")
                .contentType("text/plain")
                .build();
        DialFileMetadataDto badFile = DialFileMetadataDto.builder()
                .name("missing.txt")
                .contentType("text/plain")
                .build();
        when(dialFileClient.list(sourcePath)).thenReturn(List.of(goodFile, badFile));
        when(dialFileClient.download(sourcePath + "good.txt")).thenReturn(new byte[] {7, 8});
        when(dialFileClient.download(sourcePath + "missing.txt"))
                .thenThrow(new DialCoreClientException(HttpStatusCode.valueOf(404), "Not found", "File not found"));

        List<String> copied = fileService.copyFilesBetweenDatasets(sourceId, targetId);

        assertThat(copied).containsExactly("good.txt");
        String targetPath = bucket + "/datasets/" + targetId + "/";
        verify(dialFileClient).upload(eq(targetPath + "good.txt"), any(), eq("good.txt"), eq("text/plain"));
        verify(dialFileClient, never()).upload(eq(targetPath + "missing.txt"), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("copyFilesBetweenDatasets returns empty list and does not throw when listing source fails")
    void copyFilesBetweenDatasets_returnsEmptyList_whenListFails() {
        String sourcePath = bucket + "/datasets/" + sourceId + "/";
        when(dialFileClient.list(sourcePath))
                .thenThrow(new DialCoreClientException(HttpStatusCode.valueOf(500), "Error", "Server error"));

        List<String> copied = fileService.copyFilesBetweenDatasets(sourceId, targetId);

        assertThat(copied).isEmpty();
        verify(dialFileClient, never()).download(anyString());
        verify(dialFileClient, never()).upload(anyString(), any(), anyString(), anyString());
    }
}
