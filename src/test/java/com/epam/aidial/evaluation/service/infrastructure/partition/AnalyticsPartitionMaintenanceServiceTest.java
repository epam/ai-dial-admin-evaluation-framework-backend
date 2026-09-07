package com.epam.aidial.evaluation.service.infrastructure.partition;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.analytics.AnalyticsPartitioningProperties;
import com.epam.aidial.evaluation.constants.AnalyticsPartitioningConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.PartitionInfo;
import com.epam.aidial.evaluation.data.db.analytics.repository.AnalyticsPartitionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@DisplayName("AnalyticsPartitionMaintenanceService")
@ExtendWith(MockitoExtension.class)
class AnalyticsPartitionMaintenanceServiceTest {

    private static final String RUN_RESULTS = AnalyticsPartitioningConstants.TABLE_TEST_CASE_RUN_RESULTS;
    private static final String EVAL_SUMMARIES = AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SUMMARIES;
    private static final String EVAL_SCORES = AnalyticsPartitioningConstants.TABLE_TEST_CASE_EVAL_SCORES;
    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");

    @Mock
    private AnalyticsPartitionRepository partitionRepository;

    @Mock
    private AnalyticsPartitioningProperties properties;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private AnalyticsPartitionMaintenanceService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsPartitionMaintenanceService(partitionRepository, properties, clock);
    }

    private static PartitionInfo monthPartition(String table, YearMonth month) {
        return new PartitionInfo(
                MonthlyPartitionBounds.partitionName(table, month),
                MonthlyPartitionBounds.lowerBoundMs(month),
                MonthlyPartitionBounds.upperBoundMs(month),
                false);
    }

    @Test
    @DisplayName("ensureFuturePartitions creates only the months missing for each partitioned table")
    void ensureFuturePartitions_createsOnlyMissingMonths() {
        when(properties.getLookAheadMonths()).thenReturn(1);
        // 2026-09 already exists for run_results; 2026-10 is missing.
        when(partitionRepository.listPartitions(RUN_RESULTS))
                .thenReturn(List.of(monthPartition(RUN_RESULTS, YearMonth.of(2026, 9))));
        when(partitionRepository.listPartitions(EVAL_SUMMARIES)).thenReturn(List.of());
        when(partitionRepository.listPartitions(EVAL_SCORES)).thenReturn(List.of());

        service.ensureFuturePartitions();

        verify(partitionRepository, never())
                .createRangePartition(eq(RUN_RESULTS), eq(RUN_RESULTS + "_p202609"), anyLong(), anyLong());
        verify(partitionRepository)
                .createRangePartition(eq(RUN_RESULTS), eq(RUN_RESULTS + "_p202610"), anyLong(), anyLong());
        verify(partitionRepository)
                .createRangePartition(eq(EVAL_SUMMARIES), eq(EVAL_SUMMARIES + "_p202609"), anyLong(), anyLong());
        verify(partitionRepository)
                .createRangePartition(eq(EVAL_SUMMARIES), eq(EVAL_SUMMARIES + "_p202610"), anyLong(), anyLong());
        verify(partitionRepository)
                .createRangePartition(eq(EVAL_SCORES), eq(EVAL_SCORES + "_p202609"), anyLong(), anyLong());
        verify(partitionRepository)
                .createRangePartition(eq(EVAL_SCORES), eq(EVAL_SCORES + "_p202610"), anyLong(), anyLong());
    }

    @Test
    @DisplayName("retention-months of 0 removes nothing and never queries partitions")
    void dropExpiredPartitions_retentionZero_removesNothing() {
        when(properties.getRetentionMonths()).thenReturn(0);

        service.dropExpiredPartitions();

        verifyNoInteractions(partitionRepository);
    }

    @Test
    @DisplayName("current and default partitions are never removed, only a genuinely expired one is")
    void dropExpiredPartitions_currentAndDefaultNeverRemoved() {
        when(properties.getRetentionMonths()).thenReturn(1);
        when(properties.getArchiveInsteadOfDrop()).thenReturn(false);
        // Horizon for retention=1 relative to 2026-09 is 2026-08 start.
        PartitionInfo expired = monthPartition(RUN_RESULTS, YearMonth.of(2026, 7));
        PartitionInfo current = monthPartition(RUN_RESULTS, YearMonth.of(2026, 9));
        PartitionInfo defaultPartition = new PartitionInfo(RUN_RESULTS + "_p_default", null, null, true);
        when(partitionRepository.listPartitions(RUN_RESULTS)).thenReturn(List.of(expired, current, defaultPartition));
        when(partitionRepository.listPartitions(EVAL_SUMMARIES)).thenReturn(List.of());

        service.dropExpiredPartitions();

        verify(partitionRepository).dropPartition(RUN_RESULTS + "_p202607");
        verify(partitionRepository, never()).dropPartition(RUN_RESULTS + "_p202609");
        verify(partitionRepository, never()).dropPartition(RUN_RESULTS + "_p_default");
        verify(partitionRepository, never()).detachPartition(anyString(), anyString());
    }

    @Test
    @DisplayName("removal cap bounds how many partitions are removed in a single run")
    void dropExpiredPartitions_capsRemovalsPerRun() {
        when(properties.getRetentionMonths()).thenReturn(1);
        when(properties.getArchiveInsteadOfDrop()).thenReturn(false);
        // Many more expired months than AnalyticsPartitioningConstants.MAX_REMOVALS_PER_RUN.
        List<PartitionInfo> expired = new ArrayList<>();
        for (int month = 1; month <= 10; month++) {
            expired.add(monthPartition(RUN_RESULTS, YearMonth.of(2025, month)));
        }
        when(partitionRepository.listPartitions(RUN_RESULTS)).thenReturn(expired);
        when(partitionRepository.listPartitions(EVAL_SUMMARIES)).thenReturn(List.of());

        service.dropExpiredPartitions();

        verify(partitionRepository, times(AnalyticsPartitioningConstants.MAX_REMOVALS_PER_RUN))
                .dropPartition(anyString());
    }

    @Test
    @DisplayName("eval_summaries and eval_scores partitions for the same month are dropped together")
    void dropExpiredPartitions_summariesAndScores_droppedTogether() {
        when(properties.getRetentionMonths()).thenReturn(1);
        when(properties.getArchiveInsteadOfDrop()).thenReturn(false);
        when(partitionRepository.listPartitions(RUN_RESULTS)).thenReturn(List.of());
        when(partitionRepository.listPartitions(EVAL_SUMMARIES))
                .thenReturn(List.of(monthPartition(EVAL_SUMMARIES, YearMonth.of(2026, 7))));

        service.dropExpiredPartitions();

        verify(partitionRepository).dropPartition(EVAL_SCORES + "_p202607");
        verify(partitionRepository).dropPartition(EVAL_SUMMARIES + "_p202607");
    }

    @Test
    @DisplayName("eval_summaries and eval_scores partitions for the same month are archived together")
    void dropExpiredPartitions_summariesAndScores_archivedTogether() {
        when(properties.getRetentionMonths()).thenReturn(1);
        when(properties.getArchiveInsteadOfDrop()).thenReturn(true);
        when(partitionRepository.listPartitions(RUN_RESULTS)).thenReturn(List.of());
        when(partitionRepository.listPartitions(EVAL_SUMMARIES))
                .thenReturn(List.of(monthPartition(EVAL_SUMMARIES, YearMonth.of(2026, 7))));

        service.dropExpiredPartitions();

        verify(partitionRepository).detachPartition(EVAL_SCORES, EVAL_SCORES + "_p202607");
        verify(partitionRepository).detachPartition(EVAL_SUMMARIES, EVAL_SUMMARIES + "_p202607");
        verify(partitionRepository, never()).dropPartition(anyString());
    }

    @Test
    @DisplayName("a failure removing the paired eval_scores partition leaves the eval_summaries partition untouched")
    void dropExpiredPartitions_pairedRemovalFailure_leavesBothSidesUntouched() {
        when(properties.getRetentionMonths()).thenReturn(1);
        when(properties.getArchiveInsteadOfDrop()).thenReturn(false);
        when(partitionRepository.listPartitions(RUN_RESULTS)).thenReturn(List.of());
        when(partitionRepository.listPartitions(EVAL_SUMMARIES))
                .thenReturn(List.of(monthPartition(EVAL_SUMMARIES, YearMonth.of(2026, 7))));
        doThrow(new DataIntegrityViolationException("boom"))
                .when(partitionRepository)
                .dropPartition(EVAL_SCORES + "_p202607");

        service.dropExpiredPartitions();

        verify(partitionRepository).dropPartition(EVAL_SCORES + "_p202607");
        verify(partitionRepository, never()).dropPartition(EVAL_SUMMARIES + "_p202607");
    }

    @Test
    @DisplayName("reportDefaultPartitionUsage checks the default partition of every partitioned table")
    void reportDefaultPartitionUsage_checksAllDefaultPartitions() {
        when(partitionRepository.countRows(RUN_RESULTS + "_p_default")).thenReturn(0L);
        when(partitionRepository.countRows(EVAL_SUMMARIES + "_p_default")).thenReturn(3L);
        when(partitionRepository.countRows(EVAL_SCORES + "_p_default")).thenReturn(0L);

        service.reportDefaultPartitionUsage();

        verify(partitionRepository).countRows(RUN_RESULTS + "_p_default");
        verify(partitionRepository).countRows(EVAL_SUMMARIES + "_p_default");
        verify(partitionRepository).countRows(EVAL_SCORES + "_p_default");
    }
}
