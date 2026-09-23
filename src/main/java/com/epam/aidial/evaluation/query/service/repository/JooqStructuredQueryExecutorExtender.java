package com.epam.aidial.evaluation.query.service.repository;

import com.epam.aidial.evaluation.query.model.StructuredQuery;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * {@code @Primary} coordinator wrapping the concrete {@link JooqStructuredQueryExecutor}: runs the
 * query as-is, then folds the resulting {@link QueryResultPage} through every registered
 * {@link QueryResultPageExtender} in bean order. Delegates to the concrete executor rather than the
 * {@link StructuredQueryExecutor} interface to avoid injecting itself.
 *
 * <p>Owns failure isolation for the whole seam (see {@link QueryResultPageExtender}): each extender
 * runs in its own try/catch, so one extender throwing never fails the query and never prevents a
 * later extender from running. A {@code null} return is treated the same as a thrown exception —
 * logged and skipped — since an unenforceable "never throw" contract is no more enforceable than
 * "never return null".
 */
@Component
@Primary
@Slf4j
@RequiredArgsConstructor
class JooqStructuredQueryExecutorExtender implements StructuredQueryExecutor {

    private final JooqStructuredQueryExecutor executor;
    private final List<QueryResultPageExtender> extenders;

    @Override
    public QueryResultPage execute(StructuredQuery query) {
        QueryResultPage page = executor.execute(query);
        for (final QueryResultPageExtender extender : extenders) {
            try {
                final QueryResultPage extended = extender.extend(query, page);
                if (extended == null) {
                    log.warn(
                            "Query result page extender {} returned null for entity {}; continuing without its"
                                    + " contribution",
                            extender.getClass().getSimpleName(),
                            query.entity());
                } else {
                    page = extended;
                }
            } catch (RuntimeException e) {
                log.warn(
                        "Query result page extender {} failed for entity {}; continuing without its contribution: {}",
                        extender.getClass().getSimpleName(),
                        query.entity(),
                        e.getMessage(),
                        e);
            }
        }
        return page;
    }
}
