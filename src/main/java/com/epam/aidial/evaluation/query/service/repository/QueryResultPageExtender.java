package com.epam.aidial.evaluation.query.service.repository;

import com.epam.aidial.evaluation.query.model.StructuredQuery;

/**
 * Post-execution extension point for a {@link QueryResultPage}: adds values that cannot come from
 * the query's own SQL, typically because they live on another datasource or behind an external
 * service. {@link JooqStructuredQueryExecutorExtender} folds a page through every registered
 * implementation in bean order after {@link JooqStructuredQueryExecutor} has produced it.
 *
 * <p>An implementation decides for itself, from {@code query}, whether it applies and returns the
 * page unchanged when it does not — the coordinator holds no per-entity knowledge of which
 * extenders exist.
 *
 * <p>Contract an implementation MUST uphold:
 *
 * <ul>
 *   <li><b>Additive</b> — only adds keys to a row; never removes one.
 *   <li><b>Non-overwriting</b> — never overwrites a key a row already carries (e.g. a client-supplied
 *       {@code select} alias of the same name wins).
 *   <li><b>Order-preserving</b> — leaves the existing key order and row order, and the page's
 *       {@link QueryResultPage#totalCount()}, unchanged.
 *   <li><b>Omit, don't null</b> — when a value is unavailable for a row, that row carries no key for
 *       it; it never carries the key with a {@code null} value.
 *   <li><b>Never returns {@code null}</b> — the method itself must always return a non-null page
 *       (the input {@code page}, unchanged, is the correct "nothing to add" result).
 * </ul>
 *
 * <p>Failure isolation is the coordinator's job, not this SPI's: an implementation is free to throw
 * or, despite the contract above, to return {@code null}, and {@link JooqStructuredQueryExecutorExtender}
 * is responsible for catching/detecting either, logging, and continuing with the unextended page and
 * the remaining extenders. An implementation SHOULD NOT swallow its own failures merely to appear
 * well-behaved — that only hides them from the log.
 *
 * <p><b>Exception:</b> an implementation that bounds an external lookup with a caller-thread
 * deadline (e.g. a timed {@code Future.get}) and must cancel that lookup on timeout MAY catch the
 * expected asynchronous outcomes itself — timeout, interruption, rejection, execution failure — and
 * degrade to the page it received instead of letting them propagate. This is not the general
 * failure-swallowing the previous paragraph warns against: it MUST still log each caught failure
 * once, with the exception as the last SLF4J argument, so the coordinator never needs to log a
 * duplicate failure for it. See {@code TotalCostTestSuiteRunsPageExtender}.
 */
public interface QueryResultPageExtender {

    /**
     * Returns {@code page} extended with this implementation's derived keys, or {@code page}
     * unchanged if this extender does not apply to {@code query} or has nothing to add. MUST NOT
     * return {@code null}.
     *
     * @param query the query that produced {@code page}, after entity resolution and rewrite
     * @param page the page to extend
     * @return the extended page, honoring the additive / non-overwriting / order-preserving /
     *     omit-don't-null contract documented on this interface; never {@code null}
     */
    QueryResultPage extend(StructuredQuery query, QueryResultPage page);
}
