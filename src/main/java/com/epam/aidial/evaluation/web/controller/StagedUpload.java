package com.epam.aidial.evaluation.web.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

/**
 * A multipart upload copied to a temp file, deleted on {@link #close()}. The controller that stages an
 * upload owns it for the whole request, so services that read the file never delete what they did not
 * create.
 */
@Slf4j
record StagedUpload(Path path) implements AutoCloseable {

    static StagedUpload stage(MultipartFile file, String prefix, String suffix) throws IOException {
        StagedUpload staged = new StagedUpload(Files.createTempFile(prefix, suffix));
        try {
            file.transferTo(staged.path());
        } catch (IOException e) {
            staged.close();
            throw e;
        }
        return staged;
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete staged upload {}: {}", path, e.getMessage(), e);
        }
    }
}
