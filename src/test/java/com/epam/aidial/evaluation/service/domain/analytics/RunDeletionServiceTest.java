package com.epam.aidial.evaluation.service.domain.analytics;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.epam.aidial.evaluation.data.db.analytics.repository.RunDeletionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunDeletionService")
class RunDeletionServiceTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-10-09T12:30:00Z");
    private static final long EXPECTED_MS = FIXED_INSTANT.toEpochMilli();

    @Mock
    private RunDeletionRepository deletionRepository;

    @Mock
    private Clock clock;

    @InjectMocks
    private RunDeletionService runDeletionService;

    @Test
    @DisplayName("markDeleted inserts a tombstone row with the run ID and current timestamp")
    void markDeletedInsertsRow() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"));
        runDeletionService = new RunDeletionService(deletionRepository, fixedClock);

        // Act
        runDeletionService.markDeleted(RUN_ID);

        // Assert
        verify(deletionRepository, times(1)).insert(eq(RUN_ID), eq(EXPECTED_MS));
    }

    @Test
    @DisplayName("markDeleted is idempotent when called twice for the same run")
    void markDeletedIsIdempotent() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneId.of("UTC"));
        runDeletionService = new RunDeletionService(deletionRepository, fixedClock);

        // Act
        runDeletionService.markDeleted(RUN_ID);
        runDeletionService.markDeleted(RUN_ID);

        // Assert - both calls should reach the repository
        // (idempotence is enforced at the DB layer via ON CONFLICT DO NOTHING,
        // not at the service layer, so we expect two calls)
        verify(deletionRepository, times(2)).insert(eq(RUN_ID), eq(EXPECTED_MS));
    }
}
