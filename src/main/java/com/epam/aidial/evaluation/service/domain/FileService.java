package com.epam.aidial.evaluation.service.domain;

import static java.util.Objects.requireNonNull;

import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.client.dialcore.dto.DialFileMetadataDto;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.io.LimitingInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class FileService {

    private static final Pattern VALID_FILENAME = Pattern.compile("^[a-zA-Z0-9\\-_. ()]+$");
    private static final int MAX_FILENAME_LENGTH = 255;

    private final DialFileClient dialFileClient;
    private final DialFileRefResolver dialFileRefResolver;
    private final TestSuiteRepository testSuiteRepository;
    private final DatasetRepository datasetRepository;
    private final DialFileStorageProperties fileStorageProperties;

    public FileMetadataDto upload(UUID testSuiteId, MultipartFile file) {
        validateTestSuiteExists(testSuiteId);
        String filename = requireFilename(file);
        String efRef = dialFileRefResolver.buildEfRef(testSuiteId, filename);
        try (InputStream is = file.getInputStream()) {
            return uploadInternal(
                    filename,
                    is,
                    file.getSize(),
                    file.getContentType(),
                    efRef,
                    buildSuiteFolderPath(testSuiteId),
                    fileStorageProperties.getMaxFilesPerSuite(),
                    "suite");
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }
    }

    public FileMetadataDto uploadToDataset(UUID datasetId, MultipartFile file) {
        validateDatasetExists(datasetId);
        String filename = requireFilename(file);
        String efRef = dialFileRefResolver.buildDatasetEfRef(datasetId, filename);
        try (InputStream is = file.getInputStream()) {
            return uploadInternal(
                    filename,
                    is,
                    file.getSize(),
                    file.getContentType(),
                    efRef,
                    buildDatasetFolderPath(datasetId),
                    fileStorageProperties.getMaxFilesPerDataset(),
                    "dataset");
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }
    }

    /**
     * Writes a file into a dataset's storage, creating it or overwriting a same-name file — unlike
     * {@link #uploadToDataset}, which rejects a duplicate name with HTTP 400. Used by ZIP import, where a
     * re-imported file with the same name is meant to take effect (see the {@code test-case-zip-archive}
     * capability). Enforces {@code dial.file-storage.max-file-size-bytes} on the bytes actually read, via a
     * limiting stream that throws before the PUT is sent — {@link DialFileClient#upload} reads the whole
     * stream into memory, so the cap must apply to that read, not to a caller-declared size that may be
     * wrong (e.g. a ZIP entry header). Does not itself check the per-dataset file count limit; call
     * {@link #checkDatasetFileCapacity} once for the whole set of files an import would newly create.
     */
    public FileMetadataDto putDatasetFile(UUID datasetId, String filename, InputStream content, String contentType) {
        validateDatasetExists(datasetId);
        validateFilename(filename);
        String efRef = dialFileRefResolver.buildDatasetEfRef(datasetId, filename);
        String realPath = dialFileRefResolver.resolveToRealPath(efRef);
        InputStream limited = new LimitingInputStream(
                content,
                fileStorageProperties.getMaxFileSizeBytes(),
                max -> new ValidationException("File size exceeds maximum of " + max + " bytes"));
        DialFileMetadataDto uploaded = dialFileClient.upload(realPath, limited, filename, contentType);
        return FileMetadataDto.builder()
                .path(efRef)
                .filename(filename)
                .contentType(contentType)
                .sizeBytes(uploaded != null && uploaded.getContentLength() != null ? uploaded.getContentLength() : 0)
                .build();
    }

    /**
     * Checks that a dataset has capacity for {@code newFiles} additional files under
     * {@code dial.file-storage.max-files-per-dataset}. Files an import would overwrite (same name as an
     * existing file) do not count as new and must be excluded from {@code newFiles} by the caller.
     */
    public void checkDatasetFileCapacity(UUID datasetId, int newFiles) {
        int existing = listByDataset(datasetId).size();
        int maxFilesPerDataset = fileStorageProperties.getMaxFilesPerDataset();
        if (existing + newFiles > maxFilesPerDataset) {
            throw new ValidationException("Maximum number of files per dataset (" + maxFilesPerDataset + ") reached");
        }
    }

    public List<FileMetadataDto> list(UUID testSuiteId) {
        validateTestSuiteExists(testSuiteId);
        String suiteFolderPath = buildSuiteFolderPath(testSuiteId);
        return dialFileClient.list(suiteFolderPath).stream()
                .map(meta -> toSuiteDto(testSuiteId, meta))
                .toList();
    }

    public List<FileMetadataDto> listByDataset(UUID datasetId) {
        validateDatasetExists(datasetId);
        String datasetFolderPath = buildDatasetFolderPath(datasetId);
        return dialFileClient.list(datasetFolderPath).stream()
                .map(meta -> toDatasetDto(datasetId, meta))
                .toList();
    }

    public void downloadTo(UUID testSuiteId, String filename, OutputStream target) {
        validateTestSuiteExists(testSuiteId);
        String realPath = dialFileRefResolver.resolveToRealPath(dialFileRefResolver.buildEfRef(testSuiteId, filename));
        downloadToInternal(realPath, filename, target);
    }

    public void downloadFromDataset(UUID datasetId, String filename, OutputStream target) {
        validateDatasetExists(datasetId);
        String realPath =
                dialFileRefResolver.resolveToRealPath(dialFileRefResolver.buildDatasetEfRef(datasetId, filename));
        downloadToInternal(realPath, filename, target);
    }

    public DialFileMetadataDto getFileMetadata(UUID testSuiteId, String filename) {
        validateTestSuiteExists(testSuiteId);
        String realPath = dialFileRefResolver.resolveToRealPath(dialFileRefResolver.buildEfRef(testSuiteId, filename));
        return metadataInternal(realPath, filename);
    }

    public DialFileMetadataDto getDatasetFileMetadata(UUID datasetId, String filename) {
        validateDatasetExists(datasetId);
        String realPath =
                dialFileRefResolver.resolveToRealPath(dialFileRefResolver.buildDatasetEfRef(datasetId, filename));
        return metadataInternal(realPath, filename);
    }

    public void delete(UUID testSuiteId, String filename) {
        validateTestSuiteExists(testSuiteId);
        String realPath = dialFileRefResolver.resolveToRealPath(dialFileRefResolver.buildEfRef(testSuiteId, filename));
        deleteInternal(realPath, filename);
    }

    public void deleteByDataset(UUID datasetId, String filename) {
        validateDatasetExists(datasetId);
        String realPath =
                dialFileRefResolver.resolveToRealPath(dialFileRefResolver.buildDatasetEfRef(datasetId, filename));
        deleteInternal(realPath, filename);
    }

    /**
     * Copies all files from the source suite folder to the target suite folder.
     * Skips files that are no longer accessible (logs warning, continues).
     * Does NOT validate that the target suite exists in DB — the target suite may not yet be persisted.
     *
     * @return list of successfully copied filenames
     */
    public List<String> copyFilesBetweenSuites(UUID sourceId, UUID targetId) {
        List<String> copied = new ArrayList<>();
        String sourceFolderPath = buildSuiteFolderPath(sourceId);
        String targetFolderPath = buildSuiteFolderPath(targetId);
        List<DialFileMetadataDto> files;
        try {
            files = dialFileClient.list(sourceFolderPath);
        } catch (Exception e) {
            log.warn("Failed to list DIAL files for source suite {}: {}", sourceId, e.getMessage(), e);
            return copied;
        }
        for (DialFileMetadataDto file : files) {
            String filename = file.getName();
            String sourcePath = sourceFolderPath + filename;
            String targetPath = targetFolderPath + filename;
            try {
                byte[] bytes = dialFileClient.download(sourcePath);
                String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
                dialFileClient.upload(targetPath, new ByteArrayInputStream(bytes), filename, contentType);
                copied.add(filename);
            } catch (Exception e) {
                log.warn(
                        "Failed to copy DIAL file {}/{} to suite {}: {}",
                        sourceId,
                        filename,
                        targetId,
                        e.getMessage(),
                        e);
            }
        }
        return copied;
    }

    /**
     * Copies all files from the source dataset folder to the target dataset folder.
     * Skips files that are no longer accessible (logs warning, continues).
     * Does NOT validate that the target dataset exists in DB — the target dataset may not yet be persisted.
     *
     * @return list of successfully copied filenames
     */
    public List<String> copyFilesBetweenDatasets(UUID sourceDatasetId, UUID targetDatasetId) {
        List<String> copied = new ArrayList<>();
        String sourceFolderPath = buildDatasetFolderPath(requireNonNull(sourceDatasetId));
        String targetFolderPath = buildDatasetFolderPath(requireNonNull(targetDatasetId));
        List<DialFileMetadataDto> files;
        try {
            files = dialFileClient.list(sourceFolderPath);
        } catch (Exception e) {
            log.warn("Failed to list DIAL files for source dataset {}: {}", sourceDatasetId, e.getMessage(), e);
            return copied;
        }
        for (DialFileMetadataDto file : files) {
            String filename = file.getName();
            String sourcePath = sourceFolderPath + filename;
            String targetPath = targetFolderPath + filename;
            try {
                byte[] bytes = dialFileClient.download(sourcePath);
                String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
                dialFileClient.upload(targetPath, new ByteArrayInputStream(bytes), filename, contentType);
                copied.add(filename);
            } catch (Exception e) {
                log.warn(
                        "Failed to copy DIAL file {}/{} to dataset {}: {}",
                        sourceDatasetId,
                        filename,
                        targetDatasetId,
                        e.getMessage(),
                        e);
            }
        }
        return copied;
    }

    public void deleteAllBySuiteId(UUID testSuiteId) {
        deleteAllInFolder(buildSuiteFolderPath(testSuiteId), "suite", testSuiteId);
    }

    public void deleteAllByDatasetId(UUID datasetId) {
        deleteAllInFolder(buildDatasetFolderPath(datasetId), "dataset", datasetId);
    }

    /**
     * Shared validation and upload for the normal (duplicate-rejecting) upload endpoints. Kept distinct from
     * {@link #putDatasetFile}, which allows overwrite and has no duplicate-name check.
     */
    private FileMetadataDto uploadInternal(
            String filename,
            InputStream content,
            long size,
            String contentType,
            String efRef,
            String folderPath,
            int maxFilesPerOwner,
            String ownerKind) {
        if (size <= 0) {
            throw new ValidationException("Uploaded file is empty");
        }
        if (size > fileStorageProperties.getMaxFileSizeBytes()) {
            throw new ValidationException("File size " + size + " exceeds maximum of "
                    + fileStorageProperties.getMaxFileSizeBytes() + " bytes");
        }

        validateFilename(filename);

        String realPath = dialFileRefResolver.resolveToRealPath(efRef);

        if (dialFileClient.exists(realPath)) {
            throw new ValidationException("File with name '" + filename + "' already exists in this " + ownerKind);
        }

        List<DialFileMetadataDto> existingFiles = dialFileClient.list(folderPath);
        if (existingFiles.size() >= maxFilesPerOwner) {
            throw new ValidationException(
                    "Maximum number of files per " + ownerKind + " (" + maxFilesPerOwner + ") reached");
        }

        dialFileClient.upload(realPath, content, filename, contentType);
        return FileMetadataDto.builder()
                .path(efRef)
                .filename(filename)
                .contentType(contentType)
                .sizeBytes(size)
                .build();
    }

    private void downloadToInternal(String realPath, String filename, OutputStream target) {
        try {
            dialFileClient.downloadTo(realPath, target);
        } catch (DialCoreClientException e) {
            if (e.getStatusCode().value() == 404) {
                throw new EntityNotFoundException("File not found: " + filename);
            }
            throw e;
        }
    }

    private DialFileMetadataDto metadataInternal(String realPath, String filename) {
        try {
            return dialFileClient.metadata(realPath);
        } catch (DialCoreClientException e) {
            if (e.getStatusCode().value() == 404) {
                throw new EntityNotFoundException("File not found: " + filename);
            }
            throw e;
        }
    }

    private void deleteInternal(String realPath, String filename) {
        try {
            dialFileClient.delete(realPath);
        } catch (DialCoreClientException e) {
            if (e.getStatusCode().value() == 404) {
                throw new EntityNotFoundException("File not found: " + filename);
            }
            throw e;
        }
    }

    private void deleteAllInFolder(String folderPath, String ownerKind, UUID ownerId) {
        try {
            List<DialFileMetadataDto> files = dialFileClient.list(folderPath);
            for (DialFileMetadataDto file : files) {
                try {
                    dialFileClient.delete(folderPath + file.getName());
                } catch (Exception e) {
                    log.warn(
                            "Failed to delete DIAL file {} {}/{}: {}",
                            ownerKind,
                            ownerId,
                            file.getName(),
                            e.getMessage(),
                            e);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list/delete DIAL files for {} {}: {}", ownerKind, ownerId, e.getMessage(), e);
        }
    }

    private String buildSuiteFolderPath(UUID testSuiteId) {
        return dialFileClient.getBucket() + "/suites/" + testSuiteId + "/";
    }

    private String buildDatasetFolderPath(UUID datasetId) {
        return dialFileClient.getBucket() + "/datasets/" + datasetId + "/";
    }

    private void validateTestSuiteExists(UUID testSuiteId) {
        if (!testSuiteRepository.existsById(testSuiteId)) {
            throw new EntityNotFoundException("TestSuite not found: " + testSuiteId);
        }
    }

    private void validateDatasetExists(UUID datasetId) {
        if (!datasetRepository.existsById(datasetId)) {
            throw new EntityNotFoundException("Dataset not found: " + datasetId);
        }
    }

    private static String requireFilename(MultipartFile file) {
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new ValidationException("Filename must not be blank");
        }
        return filename;
    }

    private static void validateFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new ValidationException("Filename must not be blank");
        }
        String trimmed = filename.trim();
        if (!trimmed.equals(filename)) {
            throw new ValidationException("Filename must not have leading or trailing whitespace");
        }
        if (filename.length() > MAX_FILENAME_LENGTH) {
            throw new ValidationException("Filename exceeds maximum length of " + MAX_FILENAME_LENGTH);
        }
        if (!VALID_FILENAME.matcher(filename).matches()) {
            throw new ValidationException("Filename contains invalid characters. "
                    + "Allowed: alphanumeric, hyphen, underscore, dot, space, parentheses");
        }
    }

    private FileMetadataDto toSuiteDto(UUID testSuiteId, DialFileMetadataDto meta) {
        return toDto(dialFileRefResolver.buildEfRef(testSuiteId, meta.getName()), meta);
    }

    private FileMetadataDto toDatasetDto(UUID datasetId, DialFileMetadataDto meta) {
        return toDto(dialFileRefResolver.buildDatasetEfRef(datasetId, meta.getName()), meta);
    }

    private static FileMetadataDto toDto(String efRef, DialFileMetadataDto meta) {
        return FileMetadataDto.builder()
                .path(efRef)
                .filename(meta.getName())
                .contentType(meta.getContentType())
                .sizeBytes(meta.getContentLength() != null ? meta.getContentLength() : 0)
                .build();
    }
}
