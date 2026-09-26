package com.epam.aidial.evaluation.service.domain.zip;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.FileRefValidator;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Classifies a stored FILE field value for ZIP export, per the {@code test-case-zip-archive} capability's
 * file reference handling rule (design D3):
 *
 * <ul>
 *   <li>{@code public/…} is kept verbatim (no archive entry, no download);
 *   <li>an EF-owned reference ({@code …/datasets/…} of any dataset, or legacy {@code …/suites/…}) is
 *       materialized under {@code files/{n}/{filename}}, {@code n} unique per distinct reference in
 *       first-seen order;
 *   <li>a blank or invalid-format value is left unchanged.
 * </ul>
 *
 * <p>Classification uses {@link DialFileRefResolver} to tell an EF-owned reference from a {@code public/…}
 * one — never a hardcoded string guess: a reference resolves to a different real path only when it is
 * EF-owned, since {@link DialFileRefResolver#resolveToRealPath} passes a {@code public/…} reference through
 * unchanged.
 *
 * <p>Numbering state (the {@code ref → archive path} map) is owned by the caller, not this component, so one
 * map threaded through every {@link #classify} call across an export's rows, turns and fields gives every
 * occurrence of the same reference the same archive path, and gives distinct references distinct, ordered
 * numbers — while this component itself stays a stateless, thread-safe singleton bean.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class ZipExportFileCollector {

    private final DialFileRefResolver dialFileRefResolver;
    private final FileRefValidator fileRefValidator;

    /**
     * Classifies one stored FILE value and, for an EF-owned reference, assigns (or reuses) its archive path
     * in {@code assignedArchivePaths}.
     *
     * @param value the stored FILE field value, possibly {@code null} or blank
     * @param assignedArchivePaths mutable {@code sourceRef → files/{n}/{filename}} map, shared across the
     *     whole export so a reference already seen keeps its path and no number is reused
     * @return the classification: {@link FileClassification.Verbatim} for {@code public/…}, blank or
     *     invalid values; {@link FileClassification.EfOwned} for a materialized reference
     */
    public FileClassification classify(String value, Map<String, String> assignedArchivePaths) {
        if (value == null
                || value.isBlank()
                || !fileRefValidator.validateFormat(value).isEmpty()) {
            return new FileClassification.Verbatim(value);
        }

        String realPath = dialFileRefResolver.resolveToRealPath(value);
        if (realPath.equals(value)) {
            // public/… is passed through unchanged by resolveToRealPath; nothing else is.
            return new FileClassification.Verbatim(value);
        }

        String existingArchivePath = assignedArchivePaths.get(value);
        if (existingArchivePath != null) {
            return new FileClassification.EfOwned(existingArchivePath, realPath, false);
        }

        int n = assignedArchivePaths.size() + 1;
        String filename = dialFileRefResolver.extractFilename(value);
        String archivePath = "files/" + n + "/" + filename;
        assignedArchivePaths.put(value, archivePath);
        return new FileClassification.EfOwned(archivePath, realPath, true);
    }

    /**
     * The outcome of classifying one FILE value for export.
     */
    public sealed interface FileClassification {

        /**
         * A value written into the CSV exactly as stored, with no archive entry: {@code public/…}, blank,
         * or a value that fails {@link FileRefValidator#validateFormat}.
         */
        record Verbatim(String value) implements FileClassification {}

        /**
         * An EF-owned reference materialized into the archive.
         *
         * @param archivePath the {@code files/{n}/{filename}} path to write the CSV cell and, on first
         *     sight, the ZIP entry under
         * @param realPath the DIAL Core path to download the bytes from
         * @param firstSeen {@code true} only the first time this reference is classified in the current
         *     export; the caller downloads the bytes only then
         */
        record EfOwned(String archivePath, String realPath, boolean firstSeen) implements FileClassification {}
    }
}
