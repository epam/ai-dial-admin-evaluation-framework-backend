package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.job.ResultBatchWriter;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunSseService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates a fresh {@link PostgresResultBatchWriter} per run. Holds the one-time-built analytics
 * {@link TransactionTemplate} so each created writer instance (a plain object, not a Spring bean) can
 * demarcate its own batch-save transaction without needing AOP proxying.
 */
@Component
@LogExecution
public class PostgresResultBatchWriterFactory {

    private final TestCaseRunResultRepository resultRepository;
    private final TestSuiteRunSseService sseService;
    private final TransactionTemplate analyticsTransactionTemplate;

    public PostgresResultBatchWriterFactory(
            TestCaseRunResultRepository resultRepository,
            TestSuiteRunSseService sseService,
            @Qualifier("analyticsTransactionManager") PlatformTransactionManager analyticsTxManager) {
        this.resultRepository = resultRepository;
        this.sseService = sseService;
        this.analyticsTransactionTemplate = new TransactionTemplate(analyticsTxManager);
    }

    public ResultBatchWriter createWriter(int batchSize, UUID runId, UUID suiteId, int totalCases) {
        return new PostgresResultBatchWriter(
                resultRepository, sseService, analyticsTransactionTemplate, batchSize, runId, suiteId, totalCases);
    }
}
