package com.epam.aidial.evaluation.service.domain.zip;

import java.util.regex.Pattern;

/**
 * Sanitizes a ZIP entry's original filename to the dataset filename rules (allowed characters, max length),
 * mirroring {@code FileService}'s upload validation. Shared by {@link ZipFileImportPlanner} (D6 naming: the
 * sanitized name is what may overwrite a same-name dataset file) and the legacy {@code ZipImportService}
 * upload path, so the two never diverge on what an entry's "own name" is for overwrite purposes (D3).
 */
public final class ZipEntryFilenameSanitizer {

    private static final Pattern INVALID_FILENAME_CHARS = Pattern.compile("[^a-zA-Z0-9\\-_. ()]");

    /** The dataset filename rules' maximum length, shared with {@link ZipFileImportPlanner}'s suffixing. */
    public static final int MAX_FILENAME_LENGTH = 255;

    private ZipEntryFilenameSanitizer() {}

    /**
     * @param filename an archive entry's original filename (last path segment), possibly {@code null} or
     *     blank
     * @return a filename matching the dataset filename rules; {@code "unnamed"} if nothing usable remains
     */
    public static String sanitize(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unnamed";
        }
        String sanitized =
                INVALID_FILENAME_CHARS.matcher(filename).replaceAll("_").trim();
        if (sanitized.isEmpty()) {
            return "unnamed";
        }
        if (sanitized.length() > MAX_FILENAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_FILENAME_LENGTH);
        }
        return sanitized;
    }
}
