package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.data.db.analytics.repository.RunDeletionRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the soft-deletion of test suite run analytics data.
 * When a run is deleted, a tombstone row is inserted into the analytics {@code run_deletions} table.
 * All analytics read paths (Query DSL entities and repository methods) are repointed at {@code _active}
 * database views that exclude tombstoned runs via anti-join.
 *
 * <p>The tombstone insert is idempotent (INSERT ... ON CONFLICT DO NOTHING), making the deletion
 * operation safely retryable: if {@code markDeleted} is called twice for the same run, the second
 * call succeeds silently with no error.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class RunDeletionService {

    private final RunDeletionRepository deletionRepository;
    private final Clock clock;

    /**
     * Marks a test suite run as deleted by inserting a tombstone row.
     * The insertion is idempotent and transactional within the analytics datasource.
     *
     * @param runId the UUID of the run to mark as deleted
     */
    @Transactional("analyticsTransactionManager")
    public void markDeleted(UUID runId) {
        long deletedAtMs = clock.millis();
        deletionRepository.insert(runId, deletedAtMs);
        log.debug("Marked run {} as deleted at {}", runId, deletedAtMs);
    }
}
