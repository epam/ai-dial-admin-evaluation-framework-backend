package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Centralized validation for short-format DIAL file references ({@code {prefix}/path}).
 * Other services delegate to this component instead of implementing inline validation logic.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class FileRefValidator {

    private static final Pattern SEGMENT_PATTERN = Pattern.compile("[a-zA-Z0-9\\-_. ()]+");
    private static final String PUBLIC_PREFIX = "public";
    private static final String SUITES_SEGMENT = "suites";
    private static final String DATASETS_SEGMENT = "datasets";
    private static final Set<String> ALLOWED_EF_SEGMENTS = Set.of(SUITES_SEGMENT, DATASETS_SEGMENT);

    private final DialFileStorageProperties fileStorageProperties;

    /**
     * Validates format and suite ownership of a short-format file reference.
     * Thin facade kept for backward compatibility — new call sites should prefer
     * {@link #validateSuiteOwnership(String, UUID)} or {@link #validateDatasetOwnership(String, UUID)}.
     *
     * @return list of validation error messages; empty list means valid
     */
    public List<String> validate(String ref, UUID suiteId) {
        return validateSuiteOwnership(ref, suiteId);
    }

    /**
     * Validates format and suite ownership of a short-format file reference.
     * Strict on {@code @ef/suites/...} shape against {@code suiteId};
     * pass-through on {@code @ef/datasets/...} (wrong scope is signalled by callers when needed)
     * and {@code public/...} refs.
     *
     * @return list of validation error messages; empty list means valid
     */
    public List<String> validateSuiteOwnership(String ref, UUID suiteId) {
        List<String> formatErrors = validateFormat(ref);
        if (!formatErrors.isEmpty()) {
            return formatErrors;
        }
        return validateOwnership(ref, suiteId);
    }

    /**
     * Validates format and dataset ownership of a short-format file reference.
     * Strict on {@code @ef/datasets/...} shape against {@code datasetId};
     * pass-through on {@code @ef/suites/...} (legacy tolerance — refs in test-case data
     * that predate the dataset split) and {@code public/...} refs.
     *
     * @return list of validation error messages; empty list means valid
     */
    public List<String> validateDatasetOwnership(String ref, UUID datasetId) {
        List<String> formatErrors = validateFormat(ref);
        if (!formatErrors.isEmpty()) {
            return formatErrors;
        }
        if (datasetId == null || ref == null) {
            return List.of();
        }
        String bucketAlias = fileStorageProperties.getBucketAlias();
        if (ref.startsWith(bucketAlias + "/" + DATASETS_SEGMENT + "/")) {
            String expectedPrefix = bucketAlias + "/" + DATASETS_SEGMENT + "/" + datasetId + "/";
            if (!ref.startsWith(expectedPrefix)) {
                return List.of("File reference points to a different dataset's files: " + ref);
            }
        }
        return List.of();
    }

    /**
     * Validates only the format of a short-format file reference.
     *
     * @return list of validation error messages; empty list means valid
     */
    public List<String> validateFormat(String ref) {
        if (ref == null || ref.isBlank()) {
            return List.of("File reference must not be blank");
        }
        if (ref.startsWith("/") || ref.endsWith("/")) {
            return List.of("File reference must not have leading or trailing slash: " + ref);
        }

        Set<String> allowedPrefixes = Set.of(fileStorageProperties.getBucketAlias(), PUBLIC_PREFIX);

        int firstSlash = ref.indexOf('/');
        String prefix = firstSlash > 0 ? ref.substring(0, firstSlash) : ref;
        String rest = firstSlash > 0 ? ref.substring(firstSlash + 1) : "";

        if (!allowedPrefixes.contains(prefix)) {
            return List.of("File reference uses disallowed prefix '" + prefix + "': " + ref);
        }

        if (rest.isBlank()) {
            return List.of("File reference must have at least one path segment after the prefix: " + ref);
        }

        String[] segments = rest.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty()) {
                return List.of("File reference must not contain empty segments: " + ref);
            }
            if ("..".equals(segment)) {
                return List.of("File reference must not contain '..' path traversal: " + ref);
            }
            if (!SEGMENT_PATTERN.matcher(segment).matches()) {
                return List.of("File reference segment '" + segment + "' contains invalid characters: " + ref);
            }
        }

        if (prefix.equals(fileStorageProperties.getBucketAlias()) && !ALLOWED_EF_SEGMENTS.contains(segments[0])) {
            return List.of(
                    "File reference must start with '" + prefix + "/suites/' or '" + prefix + "/datasets/': " + ref);
        }

        return List.of();
    }

    /**
     * Validates that an {@code @ef/suites/...} file reference belongs to the given suite.
     * No-op when {@code suiteId} is {@code null} (create flow — suite UUID not yet assigned).
     * Pass-through for {@code @ef/datasets/...} (wrong scope is callers' concern) and {@code public/...}.
     *
     * @return list of validation error messages; empty list means valid
     */
    public List<String> validateOwnership(String ref, UUID suiteId) {
        if (suiteId == null || ref == null) {
            return List.of();
        }
        String bucketAlias = fileStorageProperties.getBucketAlias();
        if (ref.startsWith(bucketAlias + "/" + SUITES_SEGMENT + "/")) {
            String expectedPrefix = bucketAlias + "/" + SUITES_SEGMENT + "/" + suiteId + "/";
            if (!ref.startsWith(expectedPrefix)) {
                return List.of("File reference points to a different suite's files: " + ref);
            }
        }
        return List.of();
    }

    /**
     * Returns {@code true} when the ref is a well-formed {@code @ef/datasets/...} short ref.
     * Useful for suite-level callers to emit "wrong scope" warnings without re-implementing format parsing.
     */
    public boolean isDatasetShapedRef(String ref) {
        if (ref == null) {
            return false;
        }
        return ref.startsWith(fileStorageProperties.getBucketAlias() + "/" + DATASETS_SEGMENT + "/");
    }
}
