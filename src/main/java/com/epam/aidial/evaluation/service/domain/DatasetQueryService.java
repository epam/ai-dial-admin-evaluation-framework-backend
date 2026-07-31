package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.DatasetVisibility;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Narrow read-only facade onto the dataset domain for sibling services
 * (TestSuiteService, TestCaseService, ...). Exists so callers can satisfy the
 * cross-domain rule (see best-practices spec) without injecting DatasetService —
 * which would form a cycle, since DatasetService already depends on the suite
 * and case services for cascade flows.
 *
 * <p>Invariant: this bean depends only on {@link DatasetRepository} and MUST NOT
 * grow sibling-service dependencies. If it does, the DI graph stops being a DAG.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class DatasetQueryService {

    private final DatasetRepository datasetRepository;

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public Optional<Dataset> findById(UUID id) {
        return datasetRepository.findById(id);
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public boolean existsById(UUID id) {
        return datasetRepository.existsById(id);
    }

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public Optional<DatasetVisibility> getVisibility(UUID id) {
        return datasetRepository.findById(id).map(Dataset::getVisibility);
    }
}
