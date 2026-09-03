package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.epam.aidial.evaluation.configuration.properties.metricprovider.MetricProviderProperties;
import com.epam.aidial.evaluation.configuration.properties.metricprovider.MetricProviderProperties.ProviderEntry;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
@DisplayName("MetricProviderSyncJob")
class MetricProviderSyncJobTest {

    private static final String DIAL = "dial";
    private static final String EXTRA = "extra";
    private static final String THIRD = "third";

    private static final String RACE_LOG_MARKER = "lost a concurrent-sync race";
    private static final String FAILURE_LOG_MARKER = "Metric provider sync failed for provider";

    @Mock
    private MetricProviderSyncService metricProviderSyncService;

    private MetricProviderProperties properties;
    private MetricProviderSyncJob job;

    private Logger jobLogger;
    private Level originalLevel;
    private CapturingAppender logAppender;

    @BeforeEach
    void setUp() {
        properties = new MetricProviderProperties();
        properties.getSync().setEnabled(true);
        job = new MetricProviderSyncJob(properties, metricProviderSyncService);

        jobLogger = (Logger) LogManager.getLogger(MetricProviderSyncJob.class);
        originalLevel = jobLogger.getLevel();
        jobLogger.setLevel(Level.INFO);
        logAppender = new CapturingAppender();
        logAppender.start();
        jobLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        jobLogger.removeAppender(logAppender);
        logAppender.stop();
        jobLogger.setLevel(originalLevel);
    }

    /**
     * The two per-provider failure log lines only, matched on distinctive message text so the surrounding
     * run narration ("Starting ...", "... finished") cannot be mistaken for a failure.
     */
    private List<LogEvent> failureLogs() {
        return logAppender.events.stream()
                .filter(event -> event.getMessage().getFormattedMessage().contains(RACE_LOG_MARKER)
                        || event.getMessage().getFormattedMessage().contains(FAILURE_LOG_MARKER))
                .toList();
    }

    /**
     * A duplicate-key failure shaped the way jOOQ's exception translation delivers it: SQLSTATE 23505 on
     * a {@link SQLException} in the cause chain.
     */
    private static DataIntegrityViolationException uniqueViolation() {
        return new DataIntegrityViolationException(
                "duplicate key", new SQLException("duplicate key value violates unique constraint", "23505"));
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
            doNothing().when(metricProviderSyncService).syncOne(DIAL);
            doNothing().when(metricProviderSyncService).syncOne(THIRD);
            doThrow(new RestClientException("503 from provider"))
                    .when(metricProviderSyncService)
                    .syncOne(EXTRA);

            job.onApplicationReady();

            verify(metricProviderSyncService).syncOne(DIAL);
            verify(metricProviderSyncService).syncOne(EXTRA);
            verify(metricProviderSyncService).syncOne(THIRD);
            assertThat(failureLogs())
                    .singleElement()
                    .satisfies(event ->
                            assertThat(event.getMessage().getParameters()).contains(EXTRA));
        }

        @Test
        @DisplayName("logs a lost unique-constraint race at info without a stacktrace, and does not retry it")
        void uniqueViolation_loggedAtInfoWithoutStacktraceOrRetry() {
            givenProvider(DIAL, true);
            givenProvider(EXTRA, true);
            givenProvider(THIRD, true);
            doNothing().when(metricProviderSyncService).syncOne(DIAL);
            doNothing().when(metricProviderSyncService).syncOne(THIRD);
            doThrow(uniqueViolation()).when(metricProviderSyncService).syncOne(EXTRA);

            job.onApplicationReady();

            assertThat(failureLogs()).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getThrown()).isNull();
                assertThat(event.getMessage().getParameters()).contains(EXTRA);
            });
            verify(metricProviderSyncService, times(1)).syncOne(EXTRA);
            verify(metricProviderSyncService).syncOne(DIAL);
            verify(metricProviderSyncService).syncOne(THIRD);
        }

        @Test
        @DisplayName("logs any other provider failure at warn with the throwable attached")
        void otherFailure_loggedAtWarnWithThrowable() {
            givenProvider(DIAL, true);
            final var cause = new RestClientException("503 from provider");
            doThrow(cause).when(metricProviderSyncService).syncOne(DIAL);

            job.onApplicationReady();

            assertThat(failureLogs()).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getThrown()).isSameAs(cause);
            });
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
        @DisplayName("syncs every enabled provider and skips disabled ones on the scheduled run")
        void scheduledRun_syncsEnabledProviders() {
            givenProvider(DIAL, true);
            givenProvider(EXTRA, false);

            job.runScheduledSync();

            verify(metricProviderSyncService).syncOne(DIAL);
            verify(metricProviderSyncService, never()).syncOne(EXTRA);
        }
    }

    /**
     * Collects the job logger's events so the tests can assert on level and attached throwable. The
     * project logs through Log4j2 (see log4j2.xml), so this is a Log4j2 appender rather than a Logback
     * ListAppender.
     */
    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("capturing", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
