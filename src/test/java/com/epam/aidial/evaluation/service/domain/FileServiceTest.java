package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.client.dialcore.dto.DialFileMetadataDto;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

@DisplayName("FileService — putDatasetFile, checkDatasetFileCapacity, upload")
@ExtendWith(MockitoExtension.class)
class FileServiceTest {

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
    private final UUID datasetId = UUID.randomUUID();
    private final String bucket = "test-bucket";

    @BeforeEach
    void setUp() {
        fileService = new FileService(
                dialFileClient, dialFileRefResolver, testSuiteRepository, datasetRepository, fileStorageProperties);
    }

    @Test
    @DisplayName("putDatasetFile overwrites an existing file without a duplicate-name check")
    void putDatasetFile_overwritesExistingFile_withoutDuplicateCheck() {
        when(datasetRepository.existsById(datasetId)).thenReturn(true);
        when(fileStorageProperties.getMaxFileSizeBytes()).thenReturn(1024L);
        String efRef = "@ef/datasets/" + datasetId + "/report.pdf";
        String realPath = bucket + "/datasets/" + datasetId + "/report.pdf";
        when(dialFileRefResolver.buildDatasetEfRef(datasetId, "report.pdf")).thenReturn(efRef);
        when(dialFileRefResolver.resolveToRealPath(efRef)).thenReturn(realPath);
        when(dialFileClient.upload(eq(realPath), any(), eq("report.pdf"), eq("application/pdf")))
                .thenReturn(DialFileMetadataDto.builder()
                        .name("report.pdf")
                        .contentLength(5L)
                        .build());

        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        FileMetadataDto result = fileService.putDatasetFile(
                datasetId, "report.pdf", new ByteArrayInputStream(content), "application/pdf");

        assertThat(result.getPath()).isEqualTo(efRef);
        assertThat(result.getFilename()).isEqualTo("report.pdf");
        assertThat(result.getContentType()).isEqualTo("application/pdf");
        assertThat(result.getSizeBytes()).isEqualTo(5L);
        // No exists()/duplicate check: an already-existing file is simply overwritten.
        verify(dialFileClient, never()).exists(anyString());
    }

    @Test
    @DisplayName("putDatasetFile wraps content in a stream that rejects bytes beyond max-file-size-bytes")
    void putDatasetFile_wrapsContentInLimitingStream_thatRejectsOversizeBytesBeforePutIsSent() {
        when(datasetRepository.existsById(datasetId)).thenReturn(true);
        when(fileStorageProperties.getMaxFileSizeBytes()).thenReturn(3L);
        String efRef = "@ef/datasets/" + datasetId + "/big.bin";
        String realPath = bucket + "/datasets/" + datasetId + "/big.bin";
        when(dialFileRefResolver.buildDatasetEfRef(datasetId, "big.bin")).thenReturn(efRef);
        when(dialFileRefResolver.resolveToRealPath(efRef)).thenReturn(realPath);
        when(dialFileClient.upload(eq(realPath), any(), eq("big.bin"), eq("application/octet-stream")))
                .thenReturn(DialFileMetadataDto.builder().name("big.bin").build());

        byte[] content = "way too big".getBytes(StandardCharsets.UTF_8);
        fileService.putDatasetFile(datasetId, "big.bin", new ByteArrayInputStream(content), "application/octet-stream");

        // DialFileClient is mocked, so it never actually reads the stream it's handed (that's real behaviour
        // production relies on). Capture that stream and drain it directly: this is exactly what
        // DialFileClient.upload's real content.readAllBytes() call does, and it must throw before completing,
        // i.e. before any PUT would be sent.
        ArgumentCaptor<InputStream> streamCaptor = ArgumentCaptor.forClass(InputStream.class);
        verify(dialFileClient)
                .upload(eq(realPath), streamCaptor.capture(), eq("big.bin"), eq("application/octet-stream"));
        assertThatThrownBy(() -> streamCaptor.getValue().readAllBytes())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("exceeds maximum");
    }

    @Test
    @DisplayName("checkDatasetFileCapacity counts only new files, not overwrites")
    void checkDatasetFileCapacity_countsOnlyNewFiles() {
        when(datasetRepository.existsById(datasetId)).thenReturn(true);
        when(dialFileClient.getBucket()).thenReturn(bucket);
        String datasetFolderPath = bucket + "/datasets/" + datasetId + "/";
        when(dialFileClient.list(datasetFolderPath))
                .thenReturn(List.of(
                        DialFileMetadataDto.builder().name("a.txt").build(),
                        DialFileMetadataDto.builder().name("b.txt").build()));
        when(fileStorageProperties.getMaxFilesPerDataset()).thenReturn(3);

        // 1 new file: 2 existing + 1 new = 3, within the limit of 3.
        fileService.checkDatasetFileCapacity(datasetId, 1);

        // 2 new files: 2 existing + 2 new = 4, exceeds the limit of 3.
        assertThatThrownBy(() -> fileService.checkDatasetFileCapacity(datasetId, 2))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Maximum number of files per dataset");
    }

    @Test
    @DisplayName("the existing upload endpoint still rejects a duplicate filename")
    void uploadToDataset_stillRejectsDuplicateFilename() {
        when(datasetRepository.existsById(datasetId)).thenReturn(true);
        when(fileStorageProperties.getMaxFileSizeBytes()).thenReturn(1024L);
        String efRef = "@ef/datasets/" + datasetId + "/report.pdf";
        String realPath = bucket + "/datasets/" + datasetId + "/report.pdf";
        when(dialFileRefResolver.buildDatasetEfRef(datasetId, "report.pdf")).thenReturn(efRef);
        when(dialFileRefResolver.resolveToRealPath(efRef)).thenReturn(realPath);
        when(dialFileClient.exists(realPath)).thenReturn(true);

        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "hello".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> fileService.uploadToDataset(datasetId, file))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already exists");

        verify(dialFileClient, never()).upload(anyString(), any(), anyString(), anyString());
    }
}
