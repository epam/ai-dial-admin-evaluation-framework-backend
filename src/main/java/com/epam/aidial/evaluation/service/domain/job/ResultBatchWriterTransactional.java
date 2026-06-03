package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional wrapper for batch result persistence.
 * Separate component to avoid Spring proxy self-invocation issues.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class ResultBatchWriterTransactional {

    private final TestCaseRunResultRepository resultRepository;

    @Transactional("analyticsTransactionManager")
    public void saveBatch(List<TestCaseRunResult> batch) {
        resultRepository.saveAll(batch);
    }
}
