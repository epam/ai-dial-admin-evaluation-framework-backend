package com.epam.aidial.evaluation.runner.client.dialcore;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.DialFileStorageProperties;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves client-facing DIAL file references (with aliases) to fully resolved API paths.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class DialFileRefResolver {

    private static final String FILES_PREFIX = "files/";
    private static final String PUBLIC_PREFIX = "public";

    private final DialFileClient dialFileClient;
    private final DialFileStorageProperties fileStorageProperties;

    /**
     * Returns the set of allowed file reference prefixes.
     */
    public Set<String> getAllowedPrefixes() {
        return Set.of(fileStorageProperties.getBucketAlias(), PUBLIC_PREFIX);
    }

    /**
     * Accepts a short-format reference ({@code {prefix}/path}, no {@code files/} prefix),
     * replaces the EF alias with the real bucket name, and returns an API path suitable
     * for use with {@link DialFileClient} methods.
     *
     * @param fileRef short-format file reference, e.g. {@code @ef/suites/abc/data.csv}
     * @return resolved path, e.g. {@code real-bucket/suites/abc/data.csv}
     * @throws ValidationException if the reference is null, starts with {@code files/}, or uses a disallowed prefix
     */
    public String resolveToRealPath(String fileRef) {
        if (fileRef == null || fileRef.startsWith(FILES_PREFIX)) {
            throw new ValidationException("Invalid DIAL file reference: " + fileRef);
        }
        String prefix = extractPrefix(fileRef);

        if (!getAllowedPrefixes().contains(prefix)) {
            throw new ValidationException("Disallowed file reference prefix: " + prefix);
        }

        if (prefix.equals(fileStorageProperties.getBucketAlias())) {
            String realBucket = dialFileClient.getBucket();
            return realBucket + fileRef.substring(prefix.length());
        }
        return fileRef;
    }

    /**
     * Resolves a short-format reference to a DIAL data reference for embedding in request payloads.
     * Calls {@link #resolveToRealPath(String)} and prepends {@code files/}.
     *
     * @param fileRef short-format file reference, e.g. {@code @ef/suites/abc/data.csv}
     * @return DIAL data reference, e.g. {@code files/real-bucket/suites/abc/data.csv}
     */
    public String resolveToDialRef(String fileRef) {
        return FILES_PREFIX + resolveToRealPath(fileRef);
    }

    /**
     * Builds a client-facing short-format EF file reference for a suite using the configured alias.
     *
     * @return short-format reference, e.g. {@code @ef/suites/{suiteId}/{filename}}
     */
    public String buildEfRef(UUID suiteId, String filename) {
        return fileStorageProperties.getBucketAlias() + "/suites/" + suiteId + "/" + filename;
    }

    /**
     * Builds a client-facing short-format EF file reference for a dataset using the configured alias.
     *
     * @return short-format reference, e.g. {@code @ef/datasets/{datasetId}/{filename}}
     */
    public String buildDatasetEfRef(UUID datasetId, String filename) {
        return fileStorageProperties.getBucketAlias() + "/datasets/" + datasetId + "/" + filename;
    }

    /**
     * Extracts the filename (last path segment) from a file reference.
     */
    public String extractFilename(String fileRef) {
        int lastSlash = fileRef.lastIndexOf('/');
        return lastSlash >= 0 ? fileRef.substring(lastSlash + 1) : fileRef;
    }

    private static String extractPrefix(String path) {
        int firstSlash = path.indexOf('/');
        return firstSlash > 0 ? path.substring(0, firstSlash) : path;
    }
}
