package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunnableTestCaseCounter")
class RunnableTestCaseCounterTest {

    @Mock
    private TestCaseRepository testCaseRepository;

    @InjectMocks
    private RunnableTestCaseCounter counter;

    private final UUID datasetId = UUID.randomUUID();

    @Test
    @DisplayName("countRunnable delegates to the repository with the disabled-id exclusion")
    void countRunnableDelegates() {
        List<UUID> disabled = List.of(UUID.randomUUID());
        when(testCaseRepository.countValidByDatasetIdExcludingIds(datasetId, disabled))
                .thenReturn(5L);

        assertThat(counter.countRunnable(datasetId, disabled)).isEqualTo(5L);
    }

    @Test
    @DisplayName("countRunnable treats null disabled ids as an empty exclusion list")
    void countRunnableNullDisabled() {
        when(testCaseRepository.countValidByDatasetIdExcludingIds(eq(datasetId), eq(List.of())))
                .thenReturn(2L);

        assertThat(counter.countRunnable(datasetId, null)).isEqualTo(2L);
    }
}
