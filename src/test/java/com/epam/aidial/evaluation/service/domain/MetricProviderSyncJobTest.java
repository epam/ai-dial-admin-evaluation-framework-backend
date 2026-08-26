package com.epam.aidial.evaluation.service.domain;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.epam.aidial.evaluation.configuration.properties.metricprovider.MetricProviderProperties;
import com.epam.aidial.evaluation.configuration.properties.metricprovider.MetricProviderProperties.ProviderEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
@DisplayName("MetricProviderSyncJob")
class MetricProviderSyncJobTest {

    private static final String DIAL = "dial";
    private static final String EXTRA = "extra";
    private static final String THIRD = "third";

    @Mock
    private MetricProviderSyncService metricProviderSyncService;

    private MetricProviderProperties properties;
    private MetricProviderSyncJob job;

    @BeforeEach
    void setUp() {
        properties = new MetricProviderProperties();
        properties.getSync().setEnabled(true);
        job = new MetricProviderSyncJob(properties, metricProviderSyncService);
    }

    /**
     * Registers a provider entry under the given id, preserving configuration order.
     */
    private void givenProvider(String providerId, boolean enabled) {
        final var entry = new ProviderEntry();
        entry.setEnabled(enabled);
        entry.setBaseUrl("http://" + providerId + "-metrics:8080");
        properties.getProviders().put(providerId, entry);
    }

    @Nested
    @DisplayName("multi-provider sync")
    class MultiProviderSync {

        @Test
        @DisplayName("syncs every enabled provider entry, keyed by its map key, in configuration order")
        void allEnabled_syncsEachProviderInOrder() {
            givenProvider(DIAL, true);
            givenProvider(EXTRA, true);
            givenProvider(THIRD, true);

            job.onApplicationReady();

            final var order = inOrder(metricProviderSyncService);
            order.verify(metricProviderSyncService).syncOne(DIAL);
            order.verify(metricProviderSyncService).syncOne(EXTRA);
            order.verify(metricProviderSyncService).syncOne(THIRD);
            order.verifyNoMoreInteractions();
        }

        @Test
        @DisplayName("skips disabled provider entries and still syncs the enabled ones")
        void mixedEnabledFlags_syncsOnlyEnabledProviders() {
            givenProvider(DIAL, true);
            givenProvider(EXTRA, false);
            givenProvider(THIRD, true);

            job.onApplicationReady();

            verify(metricProviderSyncService).syncOne(DIAL);
            verify(metricProviderSyncService).syncOne(THIRD);
            verify(metricProviderSyncService, never()).syncOne(EXTRA);
        }

        @Test
        @DisplayName("calls no provider when every configured entry is disabled")
        void allDisabled_syncsNothing() {
            givenProvider(DIAL, false);
            givenProvider(EXTRA, false);

            job.onApplicationReady();

            verifyNoInteractions(metricProviderSyncService);
        }

        @Test
        @DisplayName("continues with the remaining providers after one provider fails")
        void oneProviderFails_remainingProvidersStillSynced() {
            givenProvider(DIAL, true);
            givenProvider(EXTRA, true);
            givenProvider(THIRD, true);
            doThrow(new RestClientException("503 from provider"))
                    .when(metricProviderSyncService)
                    .syncOne(EXTRA);

            job.onApplicationReady();

            verify(metricProviderSyncService).syncOne(DIAL);
            verify(metricProviderSyncService).syncOne(EXTRA);
            verify(metricProviderSyncService).syncOne(THIRD);
        }

        @Test
        @DisplayName("completes without throwing when every enabled provider fails")
        void allProvidersFail_jobCompletesWithoutThrowing() {
            givenProvider(DIAL, true);
            givenProvider(EXTRA, true);
            doThrow(new RestClientException("boom"))
                    .when(metricProviderSyncService)
                    .syncOne(DIAL);
            doThrow(new RestClientException("boom"))
                    .when(metricProviderSyncService)
                    .syncOne(EXTRA);

            job.onApplicationReady();

            verify(metricProviderSyncService).syncOne(DIAL);
            verify(metricProviderSyncService).syncOne(EXTRA);
        }
    }

    @Nested
    @DisplayName("sync gating")
    class SyncGating {

        @Test
        @DisplayName("syncs no provider when metric-providers.sync.enabled is false")
        void syncDisabled_syncsNothing() {
            properties.getSync().setEnabled(false);
            givenProvider(DIAL, true);
            givenProvider(EXTRA, true);

            job.onApplicationReady();

            verifyNoInteractions(metricProviderSyncService);
        }

        @Test
        @DisplayName("syncs nothing when the provider map is empty")
        void emptyProviderMap_syncsNothing() {
            job.onApplicationReady();

            verifyNoInteractions(metricProviderSyncService);
        }

        @Test
        @DisplayName("syncs every enabled provider on the scheduled run as well as on startup")
        void scheduledRun_syncsEnabledProviders() {
            givenProvider(DIAL, true);
            givenProvider(EXTRA, false);

            job.runScheduledSync();

            verify(metricProviderSyncService).syncOne(DIAL);
            verify(metricProviderSyncService, never()).syncOne(EXTRA);
        }
    }
}
