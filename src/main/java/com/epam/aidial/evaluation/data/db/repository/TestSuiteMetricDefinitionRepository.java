package com.epam.aidial.evaluation.data.db.repository;

import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.data.db.model.TestSuiteMetricDefinition;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestSuiteMetricDefinitionRepository {

    TestSuiteMetricDefinition save(TestSuiteMetricDefinition entity);

    Optional<TestSuiteMetricDefinition> findById(UUID id);

    Optional<TestSuiteMetricDefinition> findByIdAndTestSuiteId(UUID id, UUID testSuiteId);

    Optional<AggregatedMetricDefinition> findAggregatedByIdAndTestSuiteId(UUID id, UUID testSuiteId);

    List<AggregatedMetricDefinition> findAllAggregatedByTestSuiteId(UUID testSuiteId);

    List<AggregatedMetricDefinition> findAllEnabledAndValidAggregatedByTestSuiteId(UUID testSuiteId);

    Page<TestSuiteMetricDefinition> findAll(
            UUID testSuiteId, PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount);

    TestSuiteMetricDefinition update(TestSuiteMetricDefinition entity);

    void updateValidation(UUID id, boolean valid, String warningsJson);

    boolean deleteById(UUID id);

    void deleteByTestSuiteId(UUID testSuiteId);

    long count(UUID testSuiteId);

    /**
     * Batch-inserts all given TSMDs using JDBC batching (single round-trip).
     * Uses the supplied {@code timestamp} for {@code created_at_ms} and {@code updated_at_ms};
     * does NOT call {@link com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext}.
     */
    void batchInsert(List<TestSuiteMetricDefinition> tsmds, long timestamp);

    /**
     * Returns a batch of TSMDs for the given suite, ordered by {@code created_at_ms ASC}.
     * Uses an INNER JOIN on {@code metric_declarations} to populate {@code metric_declaration_name}.
     * TSMDs referencing deleted metric declarations are silently excluded by the JOIN.
     */
    List<TestSuiteMetricDefinition> findBatchByTestSuiteId(UUID testSuiteId, int offset, int limit);
}
