package com.epam.aidial.evaluation.service.domain.zip;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.FileService;
import com.epam.aidial.evaluation.service.domain.dto.FileMetadataDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds the naming/overwrite plan for a ZIP import's referenced files (design D6), from one {@link
 * FileService#listByDataset} call, without writing anything — so preview can reuse it unchanged.
 *
 * <p>For each referenced archive path, the plan assigns a final, sanitized dataset filename:
 *
 * <ul>
 *   <li>a name already in the target dataset is overwritten, in every import mode (D3);
 *   <li>when two different archive entries of this import would share a sanitized name, the one whose
 *       manifest {@code sourceRef} is the target dataset's own file of that name keeps it; otherwise the
 *       entry earliest in archive order does (the numeric {@code n} in {@code files/{n}/...}, ascending;
 *       {@code Integer.MAX_VALUE}, in encounter order, for a path with no numeric segment);
 *   <li>every other entry in that group gets the first {@code _1}, {@code _2}, … suffix that collides with
 *       neither the dataset listing nor an already-planned name in this import, so a suffixed name always
 *       creates a new file, never an overwrite.
 * </ul>
 *
 * <p>Capacity ({@link FileService#checkDatasetFileCapacity}) is checked once, counting only the plan's final
 * names that are new (not already in the dataset listing) — overwrites never count.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class ZipFileImportPlanner {

    private final FileService fileService;
    private final DialFileRefResolver dialFileRefResolver;

    /**
     * @param datasetId the target dataset
     * @param referencedPaths archive paths the CSV actually references (from {@link
     *     ZipCsvFileRefRewriter#scan})
     * @param manifestSourceRefByPath each manifest {@code files[]} entry's path mapped to its {@code
     *     sourceRef}; empty when the archive carries no manifest or no {@code files} list
     * @throws ValidationException if the plan's new
     *     files would exceed {@code dial.file-storage.max-files-per-dataset}
     */
    public ImportFilePlan plan(
            UUID datasetId, Set<String> referencedPaths, Map<String, String> manifestSourceRefByPath) {
        if (referencedPaths.isEmpty()) {
            return new ImportFilePlan(Map.of());
        }

        List<FileMetadataDto> existing = fileService.listByDataset(datasetId);
        Set<String> existingNames = existing.stream()
                .map(FileMetadataDto::getFilename)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> orderedPaths = referencedPaths.stream()
                .sorted(Comparator.comparingInt(ZipFileImportPlanner::archiveOrder)
                        .thenComparing(p -> p))
                .toList();

        Map<String, List<String>> pathsBySanitizedName = new LinkedHashMap<>();
        for (String path : orderedPaths) {
            String sanitized = ZipEntryFilenameSanitizer.sanitize(extractFilename(path));
            pathsBySanitizedName
                    .computeIfAbsent(sanitized, key -> new ArrayList<>())
                    .add(path);
        }

        // Every group's primary (base) name is reserved up front, before any suffix is assigned — otherwise
        // a later group's own base name (e.g. "x_1.png") could still be free when an earlier group picks its
        // first suffix, only to collide once that later group's primary claims it too. Reserving all base
        // names first makes suffix assignment see every name this import will ever produce, so it's
        // deterministic regardless of processing order.
        Set<String> plannedNames = new LinkedHashSet<>();
        Map<String, String> primaryPathByBaseName = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> group : pathsBySanitizedName.entrySet()) {
            String baseName = group.getKey();
            List<String> paths = group.getValue();
            String ownRef = dialFileRefResolver.buildDatasetEfRef(datasetId, baseName);
            String primaryPath = paths.stream()
                    .filter(path -> ownRef.equals(manifestSourceRefByPath.get(path)))
                    .findFirst()
                    .orElse(paths.get(0));
            primaryPathByBaseName.put(baseName, primaryPath);
            plannedNames.add(baseName);
        }

        Map<String, PlannedFile> plan = new LinkedHashMap<>();
        int newFileCount = 0;

        for (Map.Entry<String, List<String>> group : pathsBySanitizedName.entrySet()) {
            String baseName = group.getKey();
            String primaryPath = primaryPathByBaseName.get(baseName);

            for (String path : group.getValue()) {
                boolean isPrimary = path.equals(primaryPath);
                String finalName = isPrimary ? baseName : nextSuffixedName(baseName, existingNames, plannedNames);
                if (!isPrimary) {
                    plannedNames.add(finalName);
                }
                boolean isNew = !existingNames.contains(finalName);
                if (isNew) {
                    newFileCount++;
                }
                plan.put(
                        path,
                        new PlannedFile(finalName, dialFileRefResolver.buildDatasetEfRef(datasetId, finalName), isNew));
            }
        }

        fileService.checkDatasetFileCapacity(datasetId, newFileCount);
        return new ImportFilePlan(plan);
    }

    /**
     * Picks the first {@code _1}, {@code _2}, … suffix free of {@code existingNames} and {@code
     * plannedNames}. {@code baseName} is already sanitized to at most {@link
     * ZipEntryFilenameSanitizer#MAX_FILENAME_LENGTH} chars, but appending a suffix can push the result over
     * that cap; the stem is truncated (never the extension) so {@code stem + suffix + ext} always stays
     * within it, instead of failing {@code FileService}'s own filename-length check mid-upload.
     */
    private static String nextSuffixedName(String baseName, Set<String> existingNames, Set<String> plannedNames) {
        int dot = baseName.lastIndexOf('.');
        String base = dot > 0 ? baseName.substring(0, dot) : baseName;
        String ext = dot > 0 ? baseName.substring(dot) : "";
        int counter = 1;
        while (true) {
            String suffix = "_" + counter;
            int maxStemLength = ZipEntryFilenameSanitizer.MAX_FILENAME_LENGTH - suffix.length() - ext.length();
            String stem = maxStemLength >= 0 && base.length() > maxStemLength ? base.substring(0, maxStemLength) : base;
            String candidate = stem + suffix + ext;
            if (!existingNames.contains(candidate) && !plannedNames.contains(candidate)) {
                return candidate;
            }
            counter++;
        }
    }

    /** Archive order tie-break: the numeric {@code n} in {@code files/{n}/...}, else last. */
    private static int archiveOrder(String path) {
        String[] segments = path.split("/", 3);
        if (segments.length >= 2) {
            try {
                return Integer.parseInt(segments[1]);
            } catch (NumberFormatException e) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static String extractFilename(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    /** One referenced archive path's planned final filename, future ref, and new-vs-overwrite flag. */
    public record PlannedFile(String filename, String ref, boolean isNew) {}

    /** Maps each referenced archive path to its {@link PlannedFile}. */
    public record ImportFilePlan(Map<String, PlannedFile> byPath) {

        public PlannedFile get(String path) {
            return byPath.get(path);
        }

        public Map<String, String> pathToRef() {
            Map<String, String> refs = new LinkedHashMap<>();
            byPath.forEach((path, planned) -> refs.put(path, planned.ref()));
            return refs;
        }
    }
}
