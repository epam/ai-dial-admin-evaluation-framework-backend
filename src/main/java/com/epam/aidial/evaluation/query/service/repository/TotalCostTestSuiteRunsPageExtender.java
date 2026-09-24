package com.epam.aidial.evaluation.query.service.repository;

import com.epam.aidial.evaluation.client.dialadas.DialAdasClient;
import com.epam.aidial.evaluation.configuration.properties.query.QueryDslTestSuiteRunCostEnrichmentProperties;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.service.TestSuiteRunQueryFields;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import com.epam.aidial.evaluation.runner.util.TokenPropagationHelper;
import com.epam.aidial.evaluation.service.domain.BatchRunTotalCostLookup;
import io.opentelemetry.context.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Attaches a run's total dial-adas usage cost to each row of a {@code row}-mode {@code
 * test_suite_runs} result page, as the extension-derived {@link TestSuiteRunQueryFields#TOTAL_COST_FIELD}
 * key (design D1/D4/D5 of {@code enrich-test-suite-runs-total-cost}). Registered only when
 * {@code query-dsl.enrichment.test-suite-run.cost.enabled=true}.
 *
 * <p>One page-bounded lookup per page: {@link BatchRunTotalCostLookup} issues exactly one dial-adas
 * aggregate request for the page's distinct run {@code id}s via the dedicated, short-timeout
 * {@code testSuiteRunCostEnrichmentDialAdasClient} — never the normal shared {@link DialAdasClient}.
 *
 * <p>Skip conditions, each leaving {@code page} untouched, all decided from the query alone — a
 * {@code row} result's key set is fixed by its projection, so it is the same for every row: the query
 * targets a different entity or runs in {@code aggregate} mode; the projection does not carry the run's
 * {@code id} under the {@code id} key (omitted, renamed, or another expression aliased as {@code id});
 * the projection already carries the derived key (a client-supplied {@code select} alias wins). Once
 * those pass, every row carries a UUID {@code id} (the run's non-null primary key). An empty page
 * issues no request.
 *
 * <p>The lookup runs on the dedicated {@code testSuiteRunCostEnrichmentExecutor}, not the request
 * thread: before submission this extender captures {@link AuthorizationTokenHolder#getCredential()}
 * and {@link Context#current()} on the request thread and wraps the submitted task with both, so the
 * dial-adas request the worker thread ultimately issues carries the original caller's credential
 * header and remains part of the caller's OpenTelemetry trace (see
 * {@code DialAdasClientConfiguration}'s shared interceptors). The request thread then waits with a
 * timed {@link Future#get(long, TimeUnit)} — {@code timeoutSec} is the sole authoritative end-to-end
 * deadline; the dedicated client's matching connect/read timeout is only a best-effort cleanup
 * backstop, never a second deadline.
 *
 * <p>Every expected asynchronous outcome other than a successful, on-time result — a rejected
 * submission, a timeout, an interruption, or the lookup task itself failing — is caught here, logged
 * once with the exception last, and degrades to the page as received by this extender, with no
 * partial {@code total_cost} contribution. This extender therefore never throws out of {@link
 * #extend}, so {@link JooqStructuredQueryExecutorExtender} never needs to log a duplicate failure for
 * it and later registered extenders still run.
 */
@Component
@LogExecution
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = QueryDslTestSuiteRunCostEnrichmentProperties.PREFIX,
        name = "enabled",
        havingValue = "true")
class TotalCostTestSuiteRunsPageExtender implements QueryResultPageExtender {

    private final BatchRunTotalCostLookup batchRunTotalCostLookup;

    @Qualifier("testSuiteRunCostEnrichmentDialAdasClient")
    private final DialAdasClient dialAdasClient;

    @Qualifier("testSuiteRunCostEnrichmentExecutor")
    private final AsyncTaskExecutor executor;

    private final QueryDslTestSuiteRunCostEnrichmentProperties properties;

    @Override
    public QueryResultPage extend(StructuredQuery query, QueryResultPage page) {
        if (!applies(query)) {
            return page;
        }

        final List<UUID> runIds = candidateRunIds(page);
        final Map<UUID, Double> totalCostByRun = fetchTotalCosts(runIds);

        if (totalCostByRun.isEmpty()) {
            return page;
        }

        return new QueryResultPage(mergeRows(page.rows(), totalCostByRun), page.totalCount());
    }

    private static boolean applies(StructuredQuery query) {
        return TestSuiteRunQueryFields.ENTITY.equals(query.entity())
                && query.mode() == QueryMode.ROW
                && TestSuiteRunsRowProjection.projectsRunId(query.select())
                && TestSuiteRunsRowProjection.doesNotProjectKey(
                        query.select(), TestSuiteRunQueryFields.TOTAL_COST_FIELD);
    }

    private static List<UUID> candidateRunIds(QueryResultPage page) {
        return page.rows().stream()
                .map(TestSuiteRunsRowProjection::runId)
                .distinct()
                .toList();
    }

    /**
     * Captures the caller credential and OTel context on the request thread, submits the lookup to the
     * dedicated executor wrapped with both, and enforces {@code timeoutSec} as the authoritative
     * end-to-end deadline. Returns an empty map — never throws — on any rejected submission, timeout,
     * interruption, or lookup failure; each is logged once here with the exception last. An empty
     * {@code runIds} returns an empty map without submitting anything.
     */
    private Map<UUID, Double> fetchTotalCosts(List<UUID> runIds) {
        if (runIds.isEmpty()) {
            return Map.of();
        }

        final CallerCredential credential = AuthorizationTokenHolder.getCredential();
        final Context context = Context.current();
        final Callable<Map<UUID, Double>> lookup =
                () -> batchRunTotalCostLookup.fetchTotalCosts(runIds, dialAdasClient);

        final Future<Map<UUID, Double>> future;
        try {
            future = executor.submit(context.wrap(TokenPropagationHelper.withCredentialCallable(credential, lookup)));
        } catch (RejectedExecutionException e) {
            log.warn("Total cost enrichment lookup was rejected by the enrichment executor: {}", e.getMessage(), e);
            return Map.of();
        }

        return getCostsMap(future);
    }

    private Map<UUID, Double> getCostsMap(Future<Map<UUID, Double>> future) {
        try {
            return future.get(properties.getTimeoutSec(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn(
                    "Total cost enrichment lookup timed out after {}s: {}",
                    properties.getTimeoutSec(),
                    e.getMessage(),
                    e);
            return Map.of();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("Total cost enrichment lookup was interrupted: {}", e.getMessage(), e);
            return Map.of();
        } catch (ExecutionException e) {
            log.warn("Total cost enrichment lookup failed: {}", e.getMessage(), e);
            return Map.of();
        }
    }

    /**
     * Additive, order-preserving merge: copies only the rows that gained a value. Non-overwriting is
     * guaranteed by {@link #applies}, which skips any projection that already carries the derived key.
     */
    private static List<Map<String, Object>> mergeRows(
            List<Map<String, Object>> rows, Map<UUID, Double> totalCostByRun) {
        final List<Map<String, Object>> merged = new ArrayList<>(rows.size());
        for (final Map<String, Object> row : rows) {
            final Double value = totalCostByRun.get(TestSuiteRunsRowProjection.runId(row));
            if (value == null) {
                merged.add(row);
            } else {
                final Map<String, Object> extended = new LinkedHashMap<>(row);
                extended.put(TestSuiteRunQueryFields.TOTAL_COST_FIELD, value);
                merged.add(extended);
            }
        }
        return merged;
    }
}
