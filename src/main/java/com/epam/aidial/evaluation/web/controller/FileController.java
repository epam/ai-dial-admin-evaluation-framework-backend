package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.runner.client.dialcore.dto.DialFileMetadataDto;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.FileService;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
@RequestMapping("/api/v1/test-suites/{suiteId}/files")
@RequiredArgsConstructor
@Tag(name = "Files", description = "File management endpoints scoped to test suites")
public class FileController {

    private final FileService fileService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Upload a file to a test suite",
            description = "Uploads a file and associates it with the specified test suite via DIAL Core file storage")
    @ApiResponse(responseCode = "201", description = "File uploaded successfully")
    @ApiResponse(responseCode = "400", description = "Invalid file or limits exceeded")
    @ApiResponse(responseCode = "404", description = "Test suite not found")
    public FileMetadataDto upload(
            @Parameter(description = "Test suite ID") @PathVariable UUID suiteId,
            @RequestParam("file") MultipartFile file) {
        return fileService.upload(suiteId, file);
    }

    @GetMapping
    @Operation(
            summary = "List files in a test suite",
            description = "Returns metadata for all files associated with the specified test suite")
    @ApiResponse(responseCode = "200", description = "File list retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Test suite not found")
    public List<FileMetadataDto> list(@Parameter(description = "Test suite ID") @PathVariable UUID suiteId) {
        return fileService.list(suiteId);
    }

    @GetMapping("/{filename:.+}")
    @Operation(
            summary = "Download a file",
            description = "Streams the file content with appropriate Content-Type and Content-Disposition headers")
    @ApiResponse(responseCode = "200", description = "File downloaded successfully")
    @ApiResponse(responseCode = "404", description = "File or test suite not found")
    public ResponseEntity<StreamingResponseBody> download(
            @Parameter(description = "Test suite ID") @PathVariable UUID suiteId,
            @Parameter(description = "Filename") @PathVariable String filename) {
        DialFileMetadataDto metadata = fileService.getFileMetadata(suiteId, filename);

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

        StreamingResponseBody body = outputStream -> fileService.downloadTo(suiteId, filename, outputStream);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    @DeleteMapping("/{filename:.+}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a file", description = "Deletes the specified file from the test suite")
    @ApiResponse(responseCode = "204", description = "File deleted successfully")
    @ApiResponse(responseCode = "404", description = "File or test suite not found")
    public void delete(
            @Parameter(description = "Test suite ID") @PathVariable UUID suiteId,
            @Parameter(description = "Filename") @PathVariable String filename) {
        fileService.delete(suiteId, filename);
    }
}
