package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@code computation} request parameter to a {@code computationId} UUID.
 *
 * <p>Accepts either an explicit UUID literal or the sentinel {@code "latest"} (case-insensitive;
 * {@code null} is also treated as {@code "latest"}). When the resolution targets {@code "latest"},
 * the eval-summary repository is queried for the most recent {@code computed_at_ms} matching
 * {@code runId}.
 *
 * <p>This component does not open its own transaction. Callers are responsible for opening a
 * {@code @Transactional("analyticsTransactionManager")} scope (or an equivalent
 * {@code TransactionTemplate}) before invoking {@link #resolve(String, UUID)}.
 *
 * <p>Result conventions:
 * <ul>
 *   <li>Explicit UUID literal: returns {@code Optional.of(uuid)} without verifying the row
 *       exists — callers that require row-existence semantics SHOULD map an empty result to
 *       their domain-appropriate not-found behavior. (Today no caller verifies existence here;
 *       the repository call that follows surfaces the empty case naturally.)</li>
 *   <li>{@code "latest"} (or {@code null}): returns {@code Optional} of the latest computation
 *       UUID for the run, or {@code Optional.empty()} when there are no eval summaries for the
 *       run. Resolution reads {@code test_case_eval_summaries} — the table every caller goes on
 *       to read — so "latest" means "latest computation with readable rows" and a run whose suite
 *       had no metrics (hence no {@code run_metric_snapshots}) still resolves.</li>
 *   <li>Malformed (non-UUID, non-{@code "latest"}): throws {@link ValidationException}.</li>
 * </ul>
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class ComputationResolver {

    private static final String LATEST_SENTINEL = "latest";

    private final EvalSummaryRepository evalSummaryRepository;

    public Optional<UUID> resolve(String computation, UUID runId) {
        if (computation == null || LATEST_SENTINEL.equalsIgnoreCase(computation)) {
            return evalSummaryRepository.findLatestComputationId(runId);
        }
        try {
            return Optional.of(UUID.fromString(computation));
        } catch (IllegalArgumentException ex) {
            throw new ValidationException("Invalid computation ID: " + computation);
        }
    }
}
