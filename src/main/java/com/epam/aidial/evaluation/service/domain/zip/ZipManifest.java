package com.epam.aidial.evaluation.service.domain.zip;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import java.util.List;

/**
 * ZIP archive manifest (`manifest.json`), written by every export and read as optional input by import.
 * Carries the dataset's full field definitions (type, {@code perTurn}, {@code required}, {@code displayName},
 * {@code description}), which the CSV alone cannot reproduce, plus the source reference each archive file
 * path was exported from.
 *
 * @param formatVersion manifest schema version; only {@link #CURRENT_FORMAT_VERSION} is currently supported
 * @param testCaseSchema the dataset's field definitions at export time
 * @param files pairs each archive path (e.g. {@code files/1/report.pdf}) with the file reference it was
 *     exported from; never {@code null} (defaults to an empty list when absent on read)
 */
public record ZipManifest(int formatVersion, List<FieldDefinitionDto> testCaseSchema, List<FileEntry> files) {

    public static final int CURRENT_FORMAT_VERSION = 1;

    public ZipManifest {
        files = files != null ? files : List.of();
    }

    /**
     * Pairs one archive file path with the file reference it was exported from (or, on import, the
     * dataset's own file it should be treated as), and the source file's content type. {@code
     * contentType} is optional: absent in archives written before it existed or hand-made ones, in which
     * case import guesses it from the filename.
     */
    public record FileEntry(String path, String sourceRef, String contentType) {

        public FileEntry(String path, String sourceRef) {
            this(path, sourceRef, null);
        }
    }
}
