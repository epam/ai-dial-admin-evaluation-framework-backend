package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Narrow write surface onto the dataset domain. Owns the single "delete a
 * dataset row" operation so both DatasetService (its own delete flows) and
 * sibling services (e.g. TestSuiteService cascading a PRIVATE bound dataset)
 * share one implementation. Sibling services need this entry point to satisfy
 * the cross-domain rule without injecting DatasetService — which would form
 * a cycle, since DatasetService already depends on TestSuiteService /
 * TestCaseService.
 *
 * <p>Invariant: this bean depends only on {@link DatasetRepository} and MUST
 * NOT grow sibling-service dependencies. Keep the API minimal — only narrow
 * row-level writes that may be called from cross-domain back-edges belong here.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class DatasetCascadeService {

    private final DatasetRepository datasetRepository;

    @Transactional("metaTransactionManager")
    public void deleteById(UUID datasetId) {
        boolean deleted = datasetRepository.deleteById(datasetId);
        log.debug("Deleted dataset {} (existed={})", datasetId, deleted);
    }
}
