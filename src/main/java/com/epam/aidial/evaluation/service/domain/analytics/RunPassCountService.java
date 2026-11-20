package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.data.db.analytics.model.LatestComputation;
import com.epam.aidial.evaluation.data.db.analytics.model.PassCountTotals;
import com.epam.aidial.evaluation.data.db.analytics.model.RecordedComputation;
import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCount;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunPassCountRepository;
import com.epam.aidial.evaluation.data.db.model.CompletedRunKey;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the pass counts of completed runs. A record is append-only and idempotent: counting and inserting
 * happen in one analytics transaction and a repeat for the same run and computation leaves the first row.
 *
 * <p>The row's {@code computed_at_ms} is the greatest timestamp of the computation's eval summaries, so the
 * stored rows order like every other "latest computation" read. A computation with neither summaries nor score
 * rows is stored as the null computation with {@code computed_at_ms = 0}, so that any real computation recorded
 * later for the run supersedes it. All reads here serve the writer and are not tombstone-filtered.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class RunPassCountService {

    private static final long NULL_COMPUTATION_COMPUTED_AT_MS = 0L;

    private final RunPassCountRepository runPassCountRepository;
    private final EvalSummaryRepository evalSummaryRepository;
    private final Clock clock;

    /**
     * Counts and stores the pass counts of one run computation.
     *
     * @param computationId the computation produced for the run; null (or a computation without any rows) stores
     *     the 0-of-0 null-computation row
     * @return whether a row was inserted ({@code false} when one already existed)
     */
    @Transactional("analyticsTransactionManager")
    public boolean record(UUID runId, UUID suiteId, UUID computationId, long runCreatedAtMs) {
        return write(runId, suiteId, computationId, runCreatedAtMs);
    }

    /**
     * Each suite's latest {@code perSuiteLimit} recorded runs, newest first, each at its latest computation and
     * without deleted runs; a suite without a record is absent. Joins the caller's analytics transaction.
     */
    public Map<UUID, List<RunPassCount>> getLatestRecordedRuns(Collection<UUID> suiteIds, int perSuiteLimit) {
        return runPassCountRepository.findLatestRanked(suiteIds, perSuiteLimit);
    }

    /**
     * One row per suite (its latest recorded run, at its latest computation, without deleted runs), worst pass rate
     * first with undefined rates last, with the total. A null {@code suiteIds} means all suites; an empty collection
     * means none. Joins the caller's analytics transaction.
     */
    public Page<RunPassCount> getLatestPage(Collection<UUID> suiteIds, PageRequest pageRequest) {
        return runPassCountRepository.findLatestPage(suiteIds, pageRequest);
    }

    /**
     * Records, in one analytics transaction, every run of the batch whose latest computation has no row and is at
     * least {@code minComputationAgeMs} old; a run without eval summaries and without any row gets the
     * null-computation row.
     *
     * @return the rows inserted and the runs deferred because their latest computation is still too recent
     */
    @Transactional("analyticsTransactionManager")
    public ReconciledBatch reconcileBatch(List<CompletedRunKey> runs, long minComputationAgeMs) {
        if (runs.isEmpty()) {
            return ReconciledBatch.EMPTY;
        }
        final List<UUID> runIds = runs.stream().map(CompletedRunKey::id).toList();
        final Map<UUID, LatestComputation> latestByRun = evalSummaryRepository.findLatestComputations(runIds);
        final List<RecordedComputation> recorded = runPassCountRepository.findRecordedComputations(runIds);
        final Set<UUID> runsWithAnyRow = new HashSet<>();
        final Set<RecordedComputation> recordedPairs = new HashSet<>(recorded);
        recorded.forEach(r -> runsWithAnyRow.add(r.testSuiteRunId()));

        final long now = clock.millis();
        int written = 0;
        int deferred = 0;
        for (final CompletedRunKey run : runs) {
            final LatestComputation latest = latestByRun.get(run.id());
            final boolean missing = latest == null
                    ? !runsWithAnyRow.contains(run.id())
                    : !recordedPairs.contains(new RecordedComputation(run.id(), latest.computationId()));
            if (!missing) {
                continue;
            }
            if (latest != null && now - latest.computedAtMs() < minComputationAgeMs) {
                deferred++;
                continue;
            }
            if (write(run.id(), run.suiteId(), latest != null ? latest.computationId() : null, run.createdAt())) {
                written++;
            }
        }
        return new ReconciledBatch(written, deferred);
    }

    private boolean write(UUID runId, UUID suiteId, UUID computationId, long runCreatedAtMs) {
        final PassCountTotals totals = computationId == null
                ? new PassCountTotals(0, 0)
                : runPassCountRepository.countForComputation(runId, computationId);
        final boolean noRows = totals.total() == 0;
        final UUID storedComputation = noRows ? null : computationId;
        final long computedAtMs = noRows
                ? NULL_COMPUTATION_COMPUTED_AT_MS
                : runPassCountRepository.findComputedAtMs(runId, computationId).orElse(NULL_COMPUTATION_COMPUTED_AT_MS);
        final boolean inserted = runPassCountRepository.insertIfAbsent(RunPassCount.builder()
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .computationId(storedComputation)
                .runCreatedAtMs(runCreatedAtMs)
                .passedCount(totals.passed())
                .totalCount(totals.total())
                .computedAtMs(computedAtMs)
                .build());
        log.debug("Pass counts of run {} computation {}: inserted={}", runId, storedComputation, inserted);
        return inserted;
    }
}
