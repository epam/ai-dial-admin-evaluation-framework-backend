package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.validation.RevalidationProperties;
import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Narrow write surface for cloning a dataset and its test cases. Owns the dataset-domain writes
 * that a suite clone triggers when its source binds a PRIVATE dataset (which may bind to only one
 * suite, so the dataset must be cloned alongside the suite). Depends only on its own bounded
 * context ({@link DatasetRepository}, {@link TestCaseRepository}) plus {@link FileService} for the
 * dataset-scoped file copy, satisfying the cross-domain layering rule — sibling services drive
 * dataset/test-case writes through this entry point rather than the repositories directly.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class DatasetCloneService {

    private static final String CLONE_SUFFIX_BASE = " (clone)";

    private final DatasetRepository datasetRepository;
    private final TestCaseRepository testCaseRepository;
    private final FileService fileService;
    private final RevalidationProperties revalidationProperties;

    /**
     * Derives a unique name for the cloned dataset: base {@code "<sourceName> (clone)"}, then
     * {@code "(clone 2)"}, {@code "(clone 3)"}, … on collision (case-insensitive, via
     * {@link DatasetRepository#existsByNameIgnoreCase}). The source name is truncated as needed so
     * the result stays within {@link ValidationConstants#MAX_DATASET_NAME_LENGTH}. The
     * {@code uq_datasets_name} unique index remains the race-safe backstop.
     */
    public String deriveCloneName(String sourceName) {
        String candidate = buildCandidate(sourceName, null);
        if (!datasetRepository.existsByNameIgnoreCase(candidate)) {
            return candidate;
        }
        for (int counter = 2; ; counter++) {
            candidate = buildCandidate(sourceName, counter);
            if (!datasetRepository.existsByNameIgnoreCase(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * Copies the source dataset's DIAL files to the target dataset folder. MUST be called BEFORE the
     * DB transaction — DIAL file I/O is not transactional and must not run inside the meta tx.
     * Best-effort (skips + logs inaccessible files), delegating to {@link FileService}.
     */
    public List<String> copyDatasetFiles(UUID sourceDatasetId, UUID targetDatasetId) {
        return fileService.copyFilesBetweenDatasets(sourceDatasetId, targetDatasetId);
    }

    /**
     * Inserts the cloned dataset row (name, {@code description}, and {@code visibility} set from the
     * caller-supplied arguments, copying schema and validation state verbatim — no re-validation) and
     * copies its test cases with freshly generated ids, repointed {@code datasetId}, and
     * {@code @ef/datasets/{source}/} → {@code @ef/datasets/{new}/} rewrites in each test case's
     * {@code data}. Joins the caller's active meta transaction ({@code REQUIRED}).
     *
     * @return old → new test-case id map, used by the caller to remap the suite's
     *     {@code disabledTestCaseIds}
     */
    @Transactional("metaTransactionManager")
    public Map<UUID, UUID> cloneRowAndTestCases(
            Dataset source,
            UUID newDatasetId,
            String name,
            String description,
            String createdBy,
            long timestamp,
            DatasetVisibility visibility) {
        Dataset clone = Dataset.builder()
                .id(newDatasetId)
                .name(name)
                .description(description)
                .testCaseSchema(source.getTestCaseSchema())
                .valid(source.isValid())
                .validationWarnings(source.getValidationWarnings())
                .visibility(visibility)
                .version(0L)
                .createdBy(createdBy)
                .build();
        datasetRepository.createWithId(clone, timestamp);

        String sourcePrefix = "@ef/datasets/" + source.getId() + "/";
        String targetPrefix = "@ef/datasets/" + newDatasetId + "/";
        int batchSize = revalidationProperties.getBatchSize();

        Map<UUID, UUID> idMap = new HashMap<>();
        int offset = 0;
        while (true) {
            List<TestCase> sourceBatch = testCaseRepository.findBatchByDatasetId(source.getId(), offset, batchSize);
            if (sourceBatch.isEmpty()) {
                break;
            }
            List<TestCase> clonedCases = new ArrayList<>(sourceBatch.size());
            for (TestCase tc : sourceBatch) {
                UUID newTestCaseId = UUID.randomUUID();
                idMap.put(tc.getId(), newTestCaseId);
                clonedCases.add(TestCase.builder()
                        .id(newTestCaseId)
                        .datasetId(newDatasetId)
                        .testCaseName(tc.getTestCaseName())
                        .data(rewriteRef(tc.getData(), sourcePrefix, targetPrefix))
                        .valid(tc.isValid())
                        .validationWarnings(tc.getValidationWarnings())
                        .build());
            }
            testCaseRepository.batchInsert(clonedCases, timestamp);
            offset += sourceBatch.size();
        }
        log.debug("Cloned dataset {} -> {} with {} test cases", source.getId(), newDatasetId, idMap.size());
        return idMap;
    }

    private static String buildCandidate(String sourceName, Integer counter) {
        String suffix = counter == null ? CLONE_SUFFIX_BASE : " (clone " + counter + ")";
        int maxBaseLength = ValidationConstants.MAX_DATASET_NAME_LENGTH - suffix.length();
        String base = sourceName.length() > maxBaseLength ? sourceName.substring(0, maxBaseLength) : sourceName;
        return base + suffix;
    }

    private static String rewriteRef(String value, String sourcePrefix, String targetPrefix) {
        if (value == null) {
            return null;
        }
        return value.replace(sourcePrefix, targetPrefix);
    }
}
