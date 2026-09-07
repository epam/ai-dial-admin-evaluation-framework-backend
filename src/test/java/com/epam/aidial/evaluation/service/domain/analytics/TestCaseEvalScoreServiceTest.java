package com.epam.aidial.evaluation.service.domain.analytics;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseEvalScore;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseEvalScoreRepository;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseEvalScoreBatchWriteItemDto;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("TestCaseEvalScoreService")
@ExtendWith(MockitoExtension.class)
class TestCaseEvalScoreServiceTest {

    @Mock
    private TestCaseEvalScoreRepository testCaseEvalScoreRepository;

    @InjectMocks
    private TestCaseEvalScoreService service;

    @Captor
    private ArgumentCaptor<List<TestCaseEvalScore>> entitiesCaptor;

    @Test
    @DisplayName("batchCreate sets createdAtMs (constant for the whole batch) and computedAtMs on every entity")
    void batchCreate_setsCreatedAtMsAndComputedAtMsOnEveryEntity() {
        UUID evalSummaryId1 = UUID.randomUUID();
        UUID evalSummaryId2 = UUID.randomUUID();
        List<TestCaseEvalScoreBatchWriteItemDto> items = List.of(
                TestCaseEvalScoreBatchWriteItemDto.builder()
                        .evalSummaryId(evalSummaryId1)
                        .score(0.8)
                        .passed(true)
                        .build(),
                TestCaseEvalScoreBatchWriteItemDto.builder()
                        .evalSummaryId(evalSummaryId2)
                        .score(0.3)
                        .passed(false)
                        .build());

        service.batchCreate(1_000L, 2_000L, items);

        verify(testCaseEvalScoreRepository).saveAll(entitiesCaptor.capture());
        List<TestCaseEvalScore> saved = entitiesCaptor.getValue();
        Assertions.assertThat(saved).hasSize(2);
        Assertions.assertThat(saved).allSatisfy(entity -> {
            Assertions.assertThat(entity.getComputedAtMs()).isEqualTo(1_000L);
            Assertions.assertThat(entity.getCreatedAtMs()).isEqualTo(2_000L);
        });
        Assertions.assertThat(saved.get(0).getEvalSummaryId()).isEqualTo(evalSummaryId1);
        Assertions.assertThat(saved.get(0).getScore()).isEqualTo(0.8);
        Assertions.assertThat(saved.get(0).getPassed()).isTrue();
    }

    @Test
    @DisplayName("batchCreate is a no-op for an empty item list")
    void batchCreate_emptyItems_isNoOp() {
        service.batchCreate(1_000L, 2_000L, List.of());

        verifyNoInteractions(testCaseEvalScoreRepository);
    }
}
