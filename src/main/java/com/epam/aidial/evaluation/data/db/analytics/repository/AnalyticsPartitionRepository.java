package com.epam.aidial.evaluation.data.db.analytics.repository;

import com.epam.aidial.evaluation.data.db.analytics.model.PartitionInfo;
import java.util.List;

/**
 * Raw DDL/introspection for the declarative-partitioned analytics tables (parameterized by table
 * name so it serves {@code test_case_run_results}, {@code test_case_eval_summaries}, and
 * {@code test_case_eval_scores} identically). jOOQ has no partition-DDL API, so implementations of
 * this repository are the one place in the codebase where raw JDBC DDL execution is warranted.
 */
public interface AnalyticsPartitionRepository {

    List<PartitionInfo> listPartitions(String parentTable);

    void createRangePartition(String parentTable, String partitionName, long lowerBoundMs, long upperBoundMs);

    void dropPartition(String partitionName);

    void detachPartition(String parentTable, String partitionName);

    long countRows(String partitionName);
}
