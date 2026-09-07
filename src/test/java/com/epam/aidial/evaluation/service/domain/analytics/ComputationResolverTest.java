package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("ComputationResolver")
class ComputationResolverTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID COMPUTATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Long RUN_CREATED_AT_MS = 5_000L;

    @Mock
    private EvalSummaryRepository evalSummaryRepository;

    @InjectMocks
    private ComputationResolver resolver;

    @Test
    @DisplayName("Explicit UUID is returned as-is without querying any repository")
    void explicitUuidNeverHitsRepository() {
        Optional<UUID> result = resolver.resolve(COMPUTATION_ID.toString(), RUN_ID, RUN_CREATED_AT_MS);

        assertThat(result).contains(COMPUTATION_ID);
        verifyNoInteractions(evalSummaryRepository);
    }

    @Test
    @DisplayName("\"latest\" resolves from eval summaries, passing the run's created-at as a pruning predicate")
    void latestResolvesFromEvalSummaries() {
        when(evalSummaryRepository.findLatestComputationId(RUN_ID, RUN_CREATED_AT_MS))
                .thenReturn(Optional.of(COMPUTATION_ID));

        Optional<UUID> result = resolver.resolve("latest", RUN_ID, RUN_CREATED_AT_MS);

        assertThat(result).contains(COMPUTATION_ID);
        verify(evalSummaryRepository).findLatestComputationId(RUN_ID, RUN_CREATED_AT_MS);
    }

    @Test
    @DisplayName("\"latest\" still resolves correctly when the caller has no run-created-at available")
    void latestResolvesWithoutRunCreatedAtMs() {
        when(evalSummaryRepository.findLatestComputationId(eq(RUN_ID), isNull()))
                .thenReturn(Optional.of(COMPUTATION_ID));

        Optional<UUID> result = resolver.resolve("latest", RUN_ID, null);

        assertThat(result).contains(COMPUTATION_ID);
        verify(evalSummaryRepository).findLatestComputationId(RUN_ID, null);
    }

    @Test
    @DisplayName("\"LATEST\" is accepted case-insensitively and resolves from eval summaries")
    void latestSentinelIsCaseInsensitive() {
        when(evalSummaryRepository.findLatestComputationId(RUN_ID, RUN_CREATED_AT_MS))
                .thenReturn(Optional.of(COMPUTATION_ID));

        assertThat(resolver.resolve("LATEST", RUN_ID, RUN_CREATED_AT_MS)).contains(COMPUTATION_ID);
    }

    @Test
    @DisplayName("null computation resolves from eval summaries like \"latest\"")
    void nullResolvesFromEvalSummaries() {
        when(evalSummaryRepository.findLatestComputationId(RUN_ID, RUN_CREATED_AT_MS))
                .thenReturn(Optional.of(COMPUTATION_ID));

        Optional<UUID> result = resolver.resolve(null, RUN_ID, RUN_CREATED_AT_MS);

        assertThat(result).contains(COMPUTATION_ID);
        verify(evalSummaryRepository).findLatestComputationId(RUN_ID, RUN_CREATED_AT_MS);
    }

    @Test
    @DisplayName("\"latest\" returns empty when the run has no eval summaries")
    void latestReturnsEmptyWhenRunHasNoEvalSummaries() {
        when(evalSummaryRepository.findLatestComputationId(RUN_ID, RUN_CREATED_AT_MS))
                .thenReturn(Optional.empty());

        assertThat(resolver.resolve("latest", RUN_ID, RUN_CREATED_AT_MS)).isEmpty();
    }

    @Test
    @DisplayName("Malformed computation value throws ValidationException")
    void malformedValueThrowsValidationException() {
        assertThatThrownBy(() -> resolver.resolve("not-a-uuid", RUN_ID, RUN_CREATED_AT_MS))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not-a-uuid");
        verify(evalSummaryRepository, never()).findLatestComputationId(any(), any());
    }
}
