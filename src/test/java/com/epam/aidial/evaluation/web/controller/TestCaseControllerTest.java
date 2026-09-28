package com.epam.aidial.evaluation.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.service.domain.CsvExportService;
import com.epam.aidial.evaluation.service.domain.CsvImportService;
import com.epam.aidial.evaluation.service.domain.TestCaseService;
import com.epam.aidial.evaluation.service.domain.ZipExportService;
import com.epam.aidial.evaluation.service.domain.ZipImportService;
import com.epam.aidial.evaluation.service.domain.csv.CsvDelimiterParser;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvConflictStrategy;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportMode;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportResultDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.web.pagination.PaginationParamResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Unit tests for {@link TestCaseController}'s ZIP-vs-CSV detection (design D9): extension and content-type
 * checks first, then a {@code PK\x03\x04} magic-byte fallback via {@code ZipImportService.isZipArchive} for
 * a file whose name and content type don't already say it's a ZIP (e.g. an export renamed to {@code
 * export.bin} and sent as {@code application/octet-stream}).
 */
@DisplayName("TestCaseController ZIP detection")
@ExtendWith(MockitoExtension.class)
class TestCaseControllerTest {

    @Mock
    private TestCaseService testCaseService;

    @Mock
    private CsvExportService csvExportService;

    @Mock
    private CsvImportService csvImportService;

    @Mock
    private ZipExportService zipExportService;

    @Mock
    private ZipImportService zipImportService;

    @Mock
    private PaginationParamResolver paginationParamResolver;

    private TestCaseController controller() {
        return new TestCaseController(
                testCaseService,
                csvExportService,
                csvImportService,
                zipExportService,
                zipImportService,
                paginationParamResolver,
                new CsvDelimiterParser());
    }

    @Test
    @DisplayName("a ZIP renamed to export.bin with content type application/octet-stream is still routed to "
            + "ZipImportService, detected by the PK magic bytes")
    void importCsv_zipRenamedWithGenericContentType_detectedByMagicBytes() throws IOException {
        UUID datasetId = UUID.randomUUID();
        byte[] content = "not a real zip, only the detector is faked".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "export.bin", "application/octet-stream", content);
        when(zipImportService.isZipArchive(any())).thenReturn(true);
        List<byte[]> stagedBytes = new ArrayList<>();
        when(zipImportService.importZip(any(), any(), anyChar(), any(), any(), any()))
                .thenAnswer(inv -> {
                    stagedBytes.add(Files.readAllBytes(inv.getArgument(1, Path.class)));
                    return CsvImportResultDto.builder().totalRows(1).build();
                });

        CsvImportResultDto result =
                controller().importCsv(datasetId, file, ",", CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL, null);

        assertThat(result.getTotalRows()).isEqualTo(1);
        assertThat(stagedBytes).singleElement().isEqualTo(content);
        verify(csvImportService, never()).importCsv(any(), any(), anyLong(), anyChar(), any(), any(), any());
    }

    @Test
    @DisplayName("the controller deletes its staged ZIP after the import, including when the import throws")
    void importCsv_zip_stagedFileDeletedAfterSuccessAndFailure() throws IOException {
        UUID datasetId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile(
                "file", "data.zip", "application/zip", "zip bytes".getBytes(StandardCharsets.UTF_8));
        List<Path> stagedPaths = new ArrayList<>();
        when(zipImportService.importZip(any(), any(), anyChar(), any(), any(), any()))
                .thenAnswer(inv -> {
                    stagedPaths.add(inv.getArgument(1, Path.class));
                    return CsvImportResultDto.builder().totalRows(1).build();
                })
                .thenAnswer(inv -> {
                    stagedPaths.add(inv.getArgument(1, Path.class));
                    throw new ValidationException("boom");
                });

        controller().importCsv(datasetId, file, ",", CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL, null);
        assertThatThrownBy(() -> controller()
                        .importCsv(datasetId, file, ",", CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL, null))
                .isInstanceOf(ValidationException.class);

        assertThat(stagedPaths)
                .hasSize(2)
                .allSatisfy(path -> assertThat(Files.exists(path)).isFalse());
    }

    @Test
    @DisplayName("a .zip-named file is routed to ZipImportService without needing the magic-byte fallback")
    void importCsv_zipExtension_routedWithoutMagicByteCheck() throws IOException {
        UUID datasetId = UUID.randomUUID();
        byte[] content = "zip bytes".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "data.zip", "application/zip", content);
        when(zipImportService.importZip(any(), any(), anyChar(), any(), any(), any()))
                .thenReturn(CsvImportResultDto.builder().totalRows(2).build());

        CsvImportResultDto result =
                controller().importCsv(datasetId, file, ",", CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL, null);

        assertThat(result.getTotalRows()).isEqualTo(2);
        verify(zipImportService, never()).isZipArchive(any());
    }

    @Test
    @DisplayName("a plain CSV file (no zip extension/content-type/magic-bytes) is routed to CsvImportService")
    void importCsv_plainCsv_routedToCsvImportService() throws IOException {
        UUID datasetId = UUID.randomUUID();
        byte[] content = "testCaseName,prompt\nTC,hi".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "data.csv", "text/csv", content);
        when(zipImportService.isZipArchive(any())).thenReturn(false);
        when(csvImportService.importCsv(any(), any(), anyLong(), anyChar(), any(), any(), any()))
                .thenReturn(CsvImportResultDto.builder().totalRows(1).build());

        CsvImportResultDto result =
                controller().importCsv(datasetId, file, ",", CsvImportMode.OVERRIDE, CsvConflictStrategy.FAIL, null);

        assertThat(result.getTotalRows()).isEqualTo(1);
        verify(zipImportService, never()).importZip(any(), any(), anyChar(), any(), any(), any());
    }
}
