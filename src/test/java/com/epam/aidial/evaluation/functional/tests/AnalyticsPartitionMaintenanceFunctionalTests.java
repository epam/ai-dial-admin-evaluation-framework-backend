package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.properties.analytics.AnalyticsPartitioningProperties;
import com.epam.aidial.evaluation.constants.AnalyticsPartitioningConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.PartitionInfo;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseEvalScore;
import com.epam.aidial.evaluation.data.db.analytics.repository.AnalyticsPartitionRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseEvalScoreRepository;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.infrastructure.partition.AnalyticsPartitionMaintenanceService;
import com.epam.aidial.evaluation.service.infrastructure.partition.MonthlyPartitionBounds;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Exercises {@code AnalyticsPartitionMaintenanceService} against the real, live-partitioned
 * analytics schema. Partition creation is exercised directly against the real
 * {@code AnalyticsPartitionRepository} bean (purely additive, so safe to run against the shared
 * functional-test schema). Partition removal is exercised through a {@link ScopedPartitionRepository}
 * that only ever reports synthetic, dedicated far-future partitions created by this test — real
 * bootstrap partitions (the shared {@code _p_legacy} and near-future look-ahead partitions other
 * tests may rely on) are never visible to the service under test and can never be dropped/detached
 * by it, however far the test's fixed {@link Clock} is advanced.
 */
@DisplayName("AnalyticsPartitionMaintenanceService functional tests")
public abstract class AnalyticsPartitionMaintenanceFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private AnalyticsPartitionRepository partitionRepository;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private TestCaseEvalScoreRepository testCaseEvalScoreRepository;

    @Autowired
    @Qualifier("analyticsRawJdbcTemplate")
    private JdbcTemplate analyticsRawJdbcTemplate;

    @Test
    @DisplayName("ensureFuturePartitions creates the missing look-ahead month for all three tables, idempotently, "
            + "inheriting the parent's secondary indexes and unique/primary key")
    void ensureFuturePartitions_createsMissingMonthIdempotentlyWithInheritedIndexes() {
        // The migration already bootstraps look-ahead months 1 and 2; asking for 3 forces creation of a
        // genuinely new partition per table — purely additive, safe against the shared schema.
        AnalyticsPartitioningProperties properties = properties(3, 0, false);
        AnalyticsPartitionMaintenanceService service =
                new AnalyticsPartitionMaintenanceService(partitionRepository, properties, Clock.systemUTC());
        YearMonth thirdLookAheadMonth =
                MonthlyPartitionBounds.currentUtcMonth(Instant.now()).plusMonths(3);

        service.ensureFuturePartitions();
        service.ensureFuturePartitions(); // idempotent re-run must not error or duplicate

        for (String table : List.of(
                AnalyticsPartitioningConstants.TABLE_TEST_CASE_RUN_RESULTS,
                AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SUMMARIES,
                AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SCORES)) {
            String expectedPartition = MonthlyPartitionBounds.partitionName(table, thirdLookAheadMonth);
            List<PartitionInfo> partitions = partitionRepository.listPartitions(table);
            assertThat(partitions)
                    .filteredOn(p -> p.name().equals(expectedPartition))
                    .hasSize(1);

            String siblingPartition = MonthlyPartitionBounds.partitionName(table, thirdLookAheadMonth.minusMonths(1));
            long expectedIndexCount = countIndexes(siblingPartition);
            long actualIndexCount = countIndexes(expectedPartition);
            assertThat(actualIndexCount)
                    .as(
                            "new partition %s should inherit the same indexes as an existing sibling partition",
                            expectedPartition)
                    .isEqualTo(expectedIndexCount);
        }
    }

    @Test
    @DisplayName("dropExpiredPartitions removes an eval_summaries partition and its paired eval_scores "
            + "partition together, leaving the adjacent (not-yet-expired) month's partitions and rows intact")
    void dropExpiredPartitions_removesPairedPartitionsTogether() {
        YearMonth expiredMonth =
                MonthlyPartitionBounds.currentUtcMonth(Instant.now()).plusMonths(40);
        YearMonth survivingMonth = expiredMonth.plusMonths(1);
        String summariesExpired =
                createPartitionPair(AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SUMMARIES, expiredMonth);
        String scoresExpired =
                createPartitionPair(AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SCORES, expiredMonth);
        String summariesSurviving =
                createPartitionPair(AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SUMMARIES, survivingMonth);
        String scoresSurviving =
                createPartitionPair(AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SCORES, survivingMonth);

        UUID expiredRunId = UUID.randomUUID();
        UUID expiredSummaryId = seedSummaryWithScore(expiredRunId, MonthlyPartitionBounds.lowerBoundMs(expiredMonth));
        UUID survivingRunId = UUID.randomUUID();
        UUID survivingSummaryId =
                seedSummaryWithScore(survivingRunId, MonthlyPartitionBounds.lowerBoundMs(survivingMonth));

        ScopedPartitionRepository scoped = new ScopedPartitionRepository(
                partitionRepository, Set.of(summariesExpired, scoresExpired, summariesSurviving, scoresSurviving));
        // retention=2 relative to a clock fixed 2 months past survivingMonth's start makes exactly
        // expiredMonth's partitions expired (upper bound == horizon) while survivingMonth's are not.
        Clock fixedClock = Clock.fixed(
                Instant.ofEpochMilli(MonthlyPartitionBounds.lowerBoundMs(survivingMonth.plusMonths(2))),
                ZoneOffset.UTC);
        AnalyticsPartitionMaintenanceService service =
                new AnalyticsPartitionMaintenanceService(scoped, properties(0, 2, false), fixedClock);

        service.dropExpiredPartitions();

        assertThat(partitionRepository.listPartitions(AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SUMMARIES))
                .noneSatisfy(p -> assertThat(p.name()).isEqualTo(summariesExpired));
        assertThat(partitionRepository.listPartitions(AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SCORES))
                .noneSatisfy(p -> assertThat(p.name()).isEqualTo(scoresExpired));
        assertThat(analyticsTestDataHelper.findEvalSummariesByRunId(expiredRunId))
                .isEmpty();

        assertThat(partitionRepository.listPartitions(AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SUMMARIES))
                .anySatisfy(p -> assertThat(p.name()).isEqualTo(summariesSurviving));
        assertThat(partitionRepository.listPartitions(AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SCORES))
                .anySatisfy(p -> assertThat(p.name()).isEqualTo(scoresSurviving));
        assertThat(analyticsTestDataHelper.findEvalSummariesByRunId(survivingRunId))
                .hasSize(1);
        assertThat(partitionRepository.countRows(summariesSurviving)).isEqualTo(1L);
        assertThat(partitionRepository.countRows(scoresSurviving)).isEqualTo(1L);

        // Cleanup: drop the surviving synthetic partitions this test created.
        partitionRepository.dropPartition(summariesSurviving);
        partitionRepository.dropPartition(scoresSurviving);
    }

    @Test
    @DisplayName("dropExpiredPartitions archives (detaches, not drops) a paired eval_summaries/eval_scores "
            + "partition when archive-instead-of-drop is enabled, preserving both tables' data")
    void dropExpiredPartitions_archivesPairedPartitionsWhenConfigured() {
        YearMonth archivedMonth =
                MonthlyPartitionBounds.currentUtcMonth(Instant.now()).plusMonths(60);
        String summariesArchived =
                createPartitionPair(AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SUMMARIES, archivedMonth);
        String scoresArchived =
                createPartitionPair(AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SCORES, archivedMonth);

        UUID runId = UUID.randomUUID();
        seedSummaryWithScore(runId, MonthlyPartitionBounds.lowerBoundMs(archivedMonth));

        ScopedPartitionRepository scoped =
                new ScopedPartitionRepository(partitionRepository, Set.of(summariesArchived, scoresArchived));
        Clock fixedClock = Clock.fixed(
                Instant.ofEpochMilli(MonthlyPartitionBounds.lowerBoundMs(archivedMonth.plusMonths(2))), ZoneOffset.UTC);
        AnalyticsPartitionMaintenanceService service =
                new AnalyticsPartitionMaintenanceService(scoped, properties(0, 1, true), fixedClock);

        service.dropExpiredPartitions();

        assertThat(partitionRepository.listPartitions(AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SUMMARIES))
                .noneSatisfy(p -> assertThat(p.name()).isEqualTo(summariesArchived));
        assertThat(partitionRepository.listPartitions(AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SCORES))
                .noneSatisfy(p -> assertThat(p.name()).isEqualTo(scoresArchived));
        // Detached, not dropped: the standalone tables and their rows still exist and are queryable.
        assertThat(partitionRepository.countRows(summariesArchived)).isEqualTo(1L);
        assertThat(partitionRepository.countRows(scoresArchived)).isEqualTo(1L);

        analyticsRawJdbcTemplate.execute("DROP TABLE \"" + summariesArchived + "\"");
        analyticsRawJdbcTemplate.execute("DROP TABLE \"" + scoresArchived + "\"");
    }

    private UUID seedSummaryWithScore(UUID runId, long createdAtMs) {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Partition Maintenance Suite " + UUID.randomUUID())
                .getId();
        UUID computationId = UUID.randomUUID();
        UUID summaryId = analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "case", ExecutionStatus.SUCCESS.name(), 100L, createdAtMs);
        testCaseEvalScoreRepository.saveAll(List.of(TestCaseEvalScore.builder()
                .evalSummaryId(summaryId)
                .score(0.5)
                .passed(true)
                .computedAtMs(createdAtMs)
                .createdAtMs(createdAtMs)
                .build()));
        return summaryId;
    }

    private String createPartitionPair(String table, YearMonth month) {
        String name = MonthlyPartitionBounds.partitionName(table, month);
        partitionRepository.createRangePartition(
                table, name, MonthlyPartitionBounds.lowerBoundMs(month), MonthlyPartitionBounds.upperBoundMs(month));
        return name;
    }

    private long countIndexes(String partitionName) {
        List<String> names = analyticsRawJdbcTemplate.query(
                "SELECT indexname FROM pg_indexes WHERE tablename = ?",
                (ResultSet rs, int rowNum) -> rs.getString("indexname"),
                partitionName);
        return names.size();
    }

    private static AnalyticsPartitioningProperties properties(
            int lookAheadMonths, int retentionMonths, boolean archiveInsteadOfDrop) {
        AnalyticsPartitioningProperties properties = new AnalyticsPartitioningProperties();
        properties.setEnabled(true);
        properties.setLookAheadMonths(lookAheadMonths);
        properties.setRetentionMonths(retentionMonths);
        properties.setArchiveInsteadOfDrop(archiveInsteadOfDrop);
        properties.setMaintenanceIntervalMs(86_400_000L);
        properties.setInitialDelayMs(60_000L);
        return properties;
    }

    /** Delegates every call to the real repository, but only ever reports a fixed, test-chosen partition set. */
    private record ScopedPartitionRepository(AnalyticsPartitionRepository delegate, Set<String> visibleNames)
            implements AnalyticsPartitionRepository {

        @Override
        public List<PartitionInfo> listPartitions(String parentTable) {
            return delegate.listPartitions(parentTable).stream()
                    .filter(p -> visibleNames.contains(p.name()))
                    .toList();
        }

        @Override
        public void createRangePartition(
                String parentTable, String partitionName, long lowerBoundMs, long upperBoundMs) {
            delegate.createRangePartition(parentTable, partitionName, lowerBoundMs, upperBoundMs);
        }

        @Override
        public void dropPartition(String partitionName) {
            delegate.dropPartition(partitionName);
        }

        @Override
        public void detachPartition(String parentTable, String partitionName) {
            delegate.detachPartition(parentTable, partitionName);
        }

        @Override
        public long countRows(String partitionName) {
            return delegate.countRows(partitionName);
        }
    }
}
