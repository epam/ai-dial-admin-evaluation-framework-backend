package com.epam.aidial.evaluation.service.infrastructure.partition;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.analytics.AnalyticsPartitioningProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("AnalyticsPartitionMaintenanceJob")
@ExtendWith(MockitoExtension.class)
class AnalyticsPartitionMaintenanceJobTest {

    @Mock
    private AnalyticsPartitionMaintenanceService maintenanceService;

    @Mock
    private AnalyticsPartitioningProperties properties;

    @InjectMocks
    private AnalyticsPartitionMaintenanceJob job;

    @Test
    @DisplayName("does nothing when partitioning is disabled")
    void disabled_doesNothing() {
        when(properties.getEnabled()).thenReturn(false);

        job.maintainPartitions();

        verifyNoInteractions(maintenanceService);
    }

    @Test
    @DisplayName("creates future partitions before dropping expired ones")
    void enabled_createsBeforeDropping() {
        when(properties.getEnabled()).thenReturn(true);

        job.maintainPartitions();

        InOrder order = inOrder(maintenanceService);
        order.verify(maintenanceService).ensureFuturePartitions();
        order.verify(maintenanceService).dropExpiredPartitions();
        order.verify(maintenanceService).reportDefaultPartitionUsage();
    }

    @Test
    @DisplayName("a failure creating partitions does not prevent the drop and report phases from running")
    void ensureFuturePartitionsFails_dropAndReportStillRun() {
        when(properties.getEnabled()).thenReturn(true);
        doThrow(new RuntimeException("DB unavailable")).when(maintenanceService).ensureFuturePartitions();

        job.maintainPartitions();

        verify(maintenanceService).dropExpiredPartitions();
        verify(maintenanceService).reportDefaultPartitionUsage();
    }

    @Test
    @DisplayName("a failure dropping expired partitions does not prevent the report phase from running")
    void dropExpiredPartitionsFails_reportStillRuns() {
        when(properties.getEnabled()).thenReturn(true);
        doThrow(new RuntimeException("DB unavailable")).when(maintenanceService).dropExpiredPartitions();

        job.maintainPartitions();

        verify(maintenanceService).ensureFuturePartitions();
        verify(maintenanceService).reportDefaultPartitionUsage();
    }

    @Test
    @DisplayName("a failure in the report phase does not propagate out of the job")
    void reportDefaultPartitionUsageFails_doesNotPropagate() {
        when(properties.getEnabled()).thenReturn(true);
        doThrow(new RuntimeException("DB unavailable")).when(maintenanceService).reportDefaultPartitionUsage();

        // Should not throw.
        job.maintainPartitions();

        verify(maintenanceService).ensureFuturePartitions();
        verify(maintenanceService).dropExpiredPartitions();
    }
}
