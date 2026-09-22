package com.epam.aidial.evaluation.query.service.repository;

import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@Slf4j
@LogExecution
@RequiredArgsConstructor
class JooqStructuredQueryExecutorExtender implements StructuredQueryExecutor {

    private final JooqStructuredQueryExecutor executor;

    @Override
    public QueryResultPage execute(StructuredQuery query) {
        log.debug("Extending query for {}", query.entity());
        return executor.execute(query);
    }
}
