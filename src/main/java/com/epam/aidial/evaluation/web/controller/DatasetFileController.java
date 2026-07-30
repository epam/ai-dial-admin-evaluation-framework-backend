package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.runner.client.dialcore.dto.DialFileMetadataDto;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.FileService;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Slf4j
@RestController
@LogExecution
@Validated
@RequestMapping("/api/v1/datasets/{datasetId}/files")
@RequiredArgsConstructor
@Tag(name = "Dataset Files", description = "File management endpoints scoped to datasets")
public class DatasetFileController {

    private final FileService fileService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Upload a file to a dataset",
            description = "Uploads a file and associates it with the specified dataset via DIAL Core file storage. "
                    + "Files referenced from test-case data should be uploaded here, not under a suite.")
    @ApiResponse(
            responseCode = "201",
            description = "File uploaded successfully",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = FileMetadataDto.class),
                            examples = {
                                @ExampleObject(
                                        name = "minimal",
                                        description = "Minimal response after uploading a small CSV.",
                                        value =
                                                "{\"path\":\"@ef/datasets/11111111-1111-1111-1111-111111111111/data.csv\","
                                                        + "\"filename\":\"data.csv\",\"contentType\":\"text/csv\",\"sizeBytes\":128}"),
                                @ExampleObject(
                                        name = "full",
                                        description = "Full response after uploading a binary asset.",
                                        value =
                                                "{\"path\":\"@ef/datasets/22222222-2222-2222-2222-222222222222/report.pdf\","
                                                        + "\"filename\":\"report.pdf\","
                                                        + "\"contentType\":\"application/pdf\",\"sizeBytes\":2048576}")
                            }))
    @ApiResponse(responseCode = "400", description = "Invalid file or limits exceeded")
    @ApiResponse(responseCode = "404", description = "Dataset not found")
    public FileMetadataDto upload(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @RequestParam("file") MultipartFile file) {
        return fileService.uploadToDataset(datasetId, file);
    }

    @GetMapping
    @Operation(
            summary = "List files in a dataset",
            description = "Returns metadata for all files associated with the specified dataset")
    @ApiResponse(
            responseCode = "200",
            description = "File list retrieved successfully",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = {
                                @ExampleObject(
                                        name = "empty",
                                        description = "Dataset has no uploaded files.",
                                        value = "[]"),
                                @ExampleObject(
                                        name = "full",
                                        description = "Dataset with two uploaded files.",
                                        value = "["
                                                + "{\"path\":\"@ef/datasets/22222222-2222-2222-2222-222222222222/data.csv\","
                                                + "\"filename\":\"data.csv\",\"contentType\":\"text/csv\",\"sizeBytes\":128},"
                                                + "{\"path\":\"@ef/datasets/22222222-2222-2222-2222-222222222222/report.pdf\","
                                                + "\"filename\":\"report.pdf\","
                                                + "\"contentType\":\"application/pdf\",\"sizeBytes\":2048576}]")
                            }))
    @ApiResponse(responseCode = "404", description = "Dataset not found")
    public List<FileMetadataDto> list(@Parameter(description = "Dataset ID") @PathVariable UUID datasetId) {
        return fileService.listByDataset(datasetId);
    }

    @GetMapping("/{filename:.+}")
    @Operation(
            summary = "Download a file from a dataset",
            description = "Streams the file content with appropriate Content-Type and Content-Disposition headers")
    @ApiResponse(
            responseCode = "200",
            description = "File downloaded successfully",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                            schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "File or dataset not found")
    public ResponseEntity<StreamingResponseBody> download(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @Parameter(description = "Filename") @PathVariable String filename) {
        DialFileMetadataDto metadata = fileService.getDatasetFileMetadata(datasetId, filename);

        HttpHeaders headers = new HttpHeaders();
        if (metadata.getContentType() != null) {
            headers.setContentType(MediaType.parseMediaType(metadata.getContentType()));
        } else {
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        }
        headers.setContentDispositionFormData("attachment", filename);
        if (metadata.getContentLength() != null) {
            headers.setContentLength(metadata.getContentLength());
        }

        StreamingResponseBody body = outputStream -> fileService.downloadFromDataset(datasetId, filename, outputStream);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    @DeleteMapping("/{filename:.+}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a file from a dataset", description = "Deletes the specified file from the dataset")
    @ApiResponse(responseCode = "204", description = "File deleted successfully")
    @ApiResponse(responseCode = "404", description = "File or dataset not found")
    public void delete(
            @Parameter(description = "Dataset ID") @PathVariable UUID datasetId,
            @Parameter(description = "Filename") @PathVariable String filename) {
        fileService.deleteByDataset(datasetId, filename);
    }
}
