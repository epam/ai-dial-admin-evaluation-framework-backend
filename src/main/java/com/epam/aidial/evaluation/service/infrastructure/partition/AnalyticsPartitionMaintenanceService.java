package com.epam.aidial.evaluation.service.infrastructure.partition;

import com.epam.aidial.evaluation.configuration.properties.analytics.AnalyticsPartitioningProperties;
import com.epam.aidial.evaluation.constants.AnalyticsPartitioningConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.PartitionInfo;
import com.epam.aidial.evaluation.data.db.analytics.repository.AnalyticsPartitionRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates {@code AnalyticsPartitionRepository} DDL calls for the three partitioned analytics
 * tables: proactively creates upcoming monthly partitions, and — when
 * {@link AnalyticsPartitioningProperties#getRetentionMonths()} is positive — removes expired ones.
 *
 * <p>{@code test_case_eval_summaries} and {@code test_case_eval_scores} share identical monthly
 * partition boundaries (see {@code openspec/changes/partition-analytics-tables/design.md} D5a),
 * so their partitions for a given month are always removed together, in the same mode (both
 * dropped, or both archived) — there is no independent removal of one without the other.
 * {@code test_case_run_results} has no such pairing and is evaluated on its own.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class AnalyticsPartitionMaintenanceService {

    private final AnalyticsPartitionRepository partitionRepository;
    private final AnalyticsPartitioningProperties properties;
    private final Clock clock;

    @Transactional("analyticsTransactionManager")
    public void ensureFuturePartitions() {
        List<YearMonth> months =
                MonthlyPartitionBounds.monthsToEnsure(clock.instant(), properties.getLookAheadMonths());
        for (String table : partitionedTables()) {
            for (YearMonth month : months) {
                ensurePartitionExists(table, month);
            }
        }
    }

    @Transactional("analyticsTransactionManager")
    public void dropExpiredPartitions() {
        if (properties.getRetentionMonths() <= 0) {
            return;
        }
        long horizonMs = MonthlyPartitionBounds.lowerBoundMs(
                MonthlyPartitionBounds.currentUtcMonth(clock.instant()).minusMonths(properties.getRetentionMonths()));
        AtomicInteger budget = new AtomicInteger(AnalyticsPartitioningConstants.MAX_REMOVALS_PER_RUN);

        removeExpiredIndependent(AnalyticsPartitioningConstants.TABLE_TEST_CASE_RUN_RESULTS, horizonMs, budget);
        removeExpiredPaired(
                AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SUMMARIES,
                AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SCORES,
                horizonMs,
                budget);
    }

    @Transactional("analyticsTransactionManager")
    public void reportDefaultPartitionUsage() {
        for (String table : partitionedTables()) {
            String defaultPartition = table + AnalyticsPartitioningConstants.DEFAULT_PARTITION_SUFFIX;
            long rows = partitionRepository.countRows(defaultPartition);
            if (rows > 0) {
                log.warn(
                        "Default partition {} holds {} row(s) — a real monthly partition is missing for their timestamps",
                        defaultPartition,
                        rows);
            }
        }
    }

    /**
     * Creates the partition for {@code month} unless its range is already covered by an existing,
     * non-DEFAULT partition. A name-only check is not sufficient: right after the {@code V1.20}
     * migration (and for as long as {@code _p_legacy} remains unpruned), the current UTC month has
     * no dedicated {@code _p<yyyyMM>} partition at all — it is covered by the wide {@code _p_legacy}
     * range — so creating one under its own name would attempt to carve out a range that overlaps
     * {@code _p_legacy} and fail. The DEFAULT partition is deliberately excluded from "coverage":
     * falling into it is exactly the condition this method exists to prevent going forward.
     */
    private void ensurePartitionExists(String table, YearMonth month) {
        long monthLowerMs = MonthlyPartitionBounds.lowerBoundMs(month);
        boolean covered = partitionRepository.listPartitions(table).stream()
                .anyMatch(p -> !p.isDefault() && rangeContains(p, monthLowerMs));
        if (!covered) {
            String partitionName = MonthlyPartitionBounds.partitionName(table, month);
            partitionRepository.createRangePartition(
                    table, partitionName, monthLowerMs, MonthlyPartitionBounds.upperBoundMs(month));
        }
    }

    private static boolean rangeContains(PartitionInfo partition, long pointMs) {
        boolean atOrAfterLower = partition.lowerBoundMs() == null || pointMs >= partition.lowerBoundMs();
        boolean beforeUpper = partition.upperBoundMs() == null || pointMs < partition.upperBoundMs();
        return atOrAfterLower && beforeUpper;
    }

    private void removeExpiredIndependent(String table, long horizonMs, AtomicInteger budget) {
        for (PartitionInfo partition : partitionRepository.listPartitions(table)) {
            if (budget.get() <= 0) {
                return;
            }
            if (!isExpired(partition, horizonMs)) {
                continue;
            }
            try {
                removeOne(table, partition.name());
                budget.decrementAndGet();
            } catch (DataAccessException e) {
                log.warn("Failed to remove partition {}: {}", partition.name(), e.getMessage(), e);
            }
        }
    }

    private void removeExpiredPaired(String parentTable, String pairedTable, long horizonMs, AtomicInteger budget) {
        for (PartitionInfo partition : partitionRepository.listPartitions(parentTable)) {
            if (budget.get() <= 0) {
                return;
            }
            if (!isExpired(partition, horizonMs)) {
                continue;
            }
            String pairedName = pairedTable + partition.name().substring(parentTable.length());
            try {
                // Remove the paired table's partition first, then the parent's, so a crash between
                // the two never leaves a parent partition removed while its pair still lingers.
                removeOne(pairedTable, pairedName);
                removeOne(parentTable, partition.name());
                budget.decrementAndGet();
            } catch (DataAccessException e) {
                log.warn(
                        "Failed to remove paired partitions {} / {}; skipping this pair for this cycle: {}",
                        partition.name(),
                        pairedName,
                        e.getMessage(),
                        e);
            }
        }
    }

    private void removeOne(String table, String partitionName) {
        if (Boolean.TRUE.equals(properties.getArchiveInsteadOfDrop())) {
            partitionRepository.detachPartition(table, partitionName);
        } else {
            partitionRepository.dropPartition(partitionName);
        }
    }

    private static boolean isExpired(PartitionInfo partition, long horizonMs) {
        return !partition.isDefault() && partition.upperBoundMs() != null && partition.upperBoundMs() <= horizonMs;
    }

    private static List<String> partitionedTables() {
        return List.of(
                AnalyticsPartitioningConstants.TABLE_TEST_CASE_RUN_RESULTS,
                AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SUMMARIES,
                AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SCORES);
    }
}
