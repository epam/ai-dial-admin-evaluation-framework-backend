package com.epam.aidial.evaluation.cli.service;

import com.epam.aidial.evaluation.cli.client.source.TestSuiteApiClient;
import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteCloneRequestDto;
import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Resolves the destination clone suite ID for each configured source suite.
 *
 * <p>Idempotency rule: if a suite named {@code <sourceSuiteName>_<suffix>} already exists on the
 * source EF, it is reused as the destination without re-cloning. Only if no such suite exists is the
 * clone endpoint called.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class CloneService {

    private final TestSuiteApiClient testSuiteApiClient;

    /**
     * For each given source suite ID, resolves (or creates) the destination clone.
     *
     * @param sourceSuiteIds the source suite UUIDs to resolve
     * @param cloneSuffix    the suffix appended to cloned suite names ({@code <name>_<suffix>})
     * @return a map of {@code sourceId → destinationCloneId} for all given suites
     * @throws IllegalStateException if a source suite cannot be found on the source EF
     */
    public Map<UUID, UUID> resolveClones(List<UUID> sourceSuiteIds, String cloneSuffix) {
        final Map<UUID, UUID> result = new LinkedHashMap<>();
        for (UUID sourceSuiteId : sourceSuiteIds) {
            final UUID cloneId = resolveClone(sourceSuiteId, cloneSuffix);
            result.put(sourceSuiteId, cloneId);
        }
        return result;
    }

    /**
     * Resolves the clone ID for a single source suite.
     *
     * @param sourceSuiteId the source suite UUID
     * @param cloneSuffix   the suffix appended to the cloned suite name ({@code <name>_<suffix>})
     * @return the destination clone's UUID
     */
    public UUID resolveClone(UUID sourceSuiteId, String cloneSuffix) {
        final TestSuiteResponseDto sourceSuite = testSuiteApiClient
                .findById(sourceSuiteId)
                .orElseThrow(() -> new IllegalStateException(
                        "Source suite not found: " + sourceSuiteId + " — verify --suites and eval.source.base-url"));

        final String cloneName = sourceSuite.getName() + "_" + cloneSuffix;

        final Optional<TestSuiteResponseDto> existing = testSuiteApiClient.findByExactName(cloneName);
        if (existing.isPresent()) {
            log.info(
                    "Reusing existing clone '{}' (id={}) for source suite {} ('{}')",
                    cloneName,
                    existing.get().getId(),
                    sourceSuiteId,
                    sourceSuite.getName());
            return existing.get().getId();
        }

        final TestSuiteCloneRequestDto request =
                TestSuiteCloneRequestDto.builder().name(cloneName).build();
        final TestSuiteResponseDto clone = testSuiteApiClient.clone(sourceSuiteId, request);
        log.info(
                "Created new clone '{}' (id={}) from source suite {} ('{}')",
                cloneName,
                clone.getId(),
                sourceSuiteId,
                sourceSuite.getName());
        return clone.getId();
    }
}
