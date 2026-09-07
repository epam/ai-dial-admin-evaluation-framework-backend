package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.PartitionInfo;
import com.epam.aidial.evaluation.data.db.analytics.repository.AnalyticsPartitionRepository;
import com.epam.aidial.evaluation.service.infrastructure.partition.MonthlyPartitionBounds;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exercises {@code PostgresAnalyticsPartitionRepository} against the real partitioned schema
 * (each partitioned table already carries its legacy, look-ahead, and default partitions from
 * {@code V1.20}), run identically across all three partitioned tables.
 */
@DisplayName("PostgresAnalyticsPartitionRepository tests")
public abstract class PostgresAnalyticsPartitionRepositoryFunctionalTests extends BaseFunctionalTest {

    private static final String[] PARTITIONED_TABLES = {
        "test_case_run_results", "test_case_eval_summaries", "test_case_eval_scores"
    };

    @Autowired
    private AnalyticsPartitionRepository partitionRepository;

    @ParameterizedTest
    @ValueSource(strings = {"test_case_run_results", "test_case_eval_summaries", "test_case_eval_scores"})
    @DisplayName("listPartitions returns the legacy and default partitions already created by V1.20")
    void listPartitions_returnsLegacyAndDefaultPartitions(String table) {
        List<PartitionInfo> partitions = partitionRepository.listPartitions(table);

        assertThat(partitions).isNotEmpty();
        assertThat(partitions)
                .anySatisfy(p -> assertThat(p.name()).isEqualTo(table + "_p_legacy"))
                .anySatisfy(p -> {
                    assertThat(p.name()).isEqualTo(table + "_p_default");
                    assertThat(p.isDefault()).isTrue();
                });

        PartitionInfo legacy = partitions.stream()
                .filter(p -> p.name().equals(table + "_p_legacy"))
                .findFirst()
                .orElseThrow();
        assertThat(legacy.lowerBoundMs()).isNull(); // MINVALUE
        assertThat(legacy.upperBoundMs()).isNotNull();
        assertThat(legacy.isDefault()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"test_case_run_results", "test_case_eval_summaries", "test_case_eval_scores"})
    @DisplayName("createRangePartition creates a new partition with the requested bounds, initially empty")
    void createRangePartition_createsPartitionWithBounds(String table) {
        YearMonth farFutureMonth = farFutureMonth(table);
        String partitionName = MonthlyPartitionBounds.partitionName(table, farFutureMonth);
        long lower = MonthlyPartitionBounds.lowerBoundMs(farFutureMonth);
        long upper = MonthlyPartitionBounds.upperBoundMs(farFutureMonth);

        partitionRepository.createRangePartition(table, partitionName, lower, upper);

        List<PartitionInfo> partitions = partitionRepository.listPartitions(table);
        assertThat(partitions).anySatisfy(p -> {
            assertThat(p.name()).isEqualTo(partitionName);
            assertThat(p.lowerBoundMs()).isEqualTo(lower);
            assertThat(p.upperBoundMs()).isEqualTo(upper);
            assertThat(p.isDefault()).isFalse();
        });
        assertThat(partitionRepository.countRows(partitionName)).isEqualTo(0L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"test_case_run_results", "test_case_eval_summaries", "test_case_eval_scores"})
    @DisplayName("dropPartition removes the partition from the parent")
    void dropPartition_removesPartition(String table) {
        YearMonth month = farFutureMonth(table).plusMonths(1);
        String partitionName = MonthlyPartitionBounds.partitionName(table, month);
        partitionRepository.createRangePartition(
                table,
                partitionName,
                MonthlyPartitionBounds.lowerBoundMs(month),
                MonthlyPartitionBounds.upperBoundMs(month));
        assertThat(partitionRepository.listPartitions(table))
                .anySatisfy(p -> assertThat(p.name()).isEqualTo(partitionName));

        partitionRepository.dropPartition(partitionName);

        assertThat(partitionRepository.listPartitions(table))
                .noneSatisfy(p -> assertThat(p.name()).isEqualTo(partitionName));
    }

    @ParameterizedTest
    @ValueSource(strings = {"test_case_run_results", "test_case_eval_summaries", "test_case_eval_scores"})
    @DisplayName("detachPartition removes the partition from the parent without dropping the underlying table")
    void detachPartition_removesFromParentButKeepsTable(String table) {
        YearMonth month = farFutureMonth(table).plusMonths(2);
        String partitionName = MonthlyPartitionBounds.partitionName(table, month);
        partitionRepository.createRangePartition(
                table,
                partitionName,
                MonthlyPartitionBounds.lowerBoundMs(month),
                MonthlyPartitionBounds.upperBoundMs(month));

        partitionRepository.detachPartition(table, partitionName);

        assertThat(partitionRepository.listPartitions(table))
                .noneSatisfy(p -> assertThat(p.name()).isEqualTo(partitionName));
        // The detached table is still a real, queryable, standalone table — not dropped.
        assertThat(partitionRepository.countRows(partitionName)).isEqualTo(0L);
    }

    /** A month far enough in the future that it cannot collide with V1.20's bootstrap partitions. */
    private static YearMonth farFutureMonth(String table) {
        return MonthlyPartitionBounds.currentUtcMonth(Instant.now()).plusYears(50 + Math.abs(table.hashCode() % 10));
    }
}
