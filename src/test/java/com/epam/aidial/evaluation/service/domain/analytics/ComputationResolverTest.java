package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @Mock
    private EvalSummaryRepository evalSummaryRepository;

    @InjectMocks
    private ComputationResolver resolver;

    @Test
    @DisplayName("Explicit UUID is returned as-is without querying any repository")
    void explicitUuidNeverHitsRepository() {
        Optional<UUID> result = resolver.resolve(COMPUTATION_ID.toString(), RUN_ID);

        assertThat(result).contains(COMPUTATION_ID);
        verifyNoInteractions(evalSummaryRepository);
    }

    @Test
    @DisplayName("\"latest\" resolves to the eval summaries' latest computation")
    void latestResolvesFromEvalSummaries() {
        when(evalSummaryRepository.findLatestComputationId(RUN_ID)).thenReturn(Optional.of(COMPUTATION_ID));

        Optional<UUID> result = resolver.resolve("latest", RUN_ID);

        assertThat(result).contains(COMPUTATION_ID);
        verify(evalSummaryRepository).findLatestComputationId(RUN_ID);
    }

    @Test
    @DisplayName("\"LATEST\" is accepted case-insensitively and resolves from eval summaries")
    void latestSentinelIsCaseInsensitive() {
        when(evalSummaryRepository.findLatestComputationId(RUN_ID)).thenReturn(Optional.of(COMPUTATION_ID));

        assertThat(resolver.resolve("LATEST", RUN_ID)).contains(COMPUTATION_ID);
    }

    @Test
    @DisplayName("null computation resolves from eval summaries like \"latest\"")
    void nullResolvesFromEvalSummaries() {
        when(evalSummaryRepository.findLatestComputationId(RUN_ID)).thenReturn(Optional.of(COMPUTATION_ID));

        Optional<UUID> result = resolver.resolve(null, RUN_ID);

        assertThat(result).contains(COMPUTATION_ID);
        verify(evalSummaryRepository).findLatestComputationId(RUN_ID);
    }

    @Test
    @DisplayName("\"latest\" returns empty when the run has no eval summaries")
    void latestReturnsEmptyWhenRunHasNoEvalSummaries() {
        when(evalSummaryRepository.findLatestComputationId(RUN_ID)).thenReturn(Optional.empty());

        assertThat(resolver.resolve("latest", RUN_ID)).isEmpty();
    }

    @Test
    @DisplayName("Malformed computation value throws ValidationException")
    void malformedValueThrowsValidationException() {
        assertThatThrownBy(() -> resolver.resolve("not-a-uuid", RUN_ID))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not-a-uuid");
        verify(evalSummaryRepository, never()).findLatestComputationId(any());
    }
}
