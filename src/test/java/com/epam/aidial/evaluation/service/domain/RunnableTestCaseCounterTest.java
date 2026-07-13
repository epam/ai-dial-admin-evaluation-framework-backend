package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.service.domain.job.RunnableTestCaseSelector;
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
    private RunnableTestCaseSelector runnableTestCaseSelector;

    @InjectMocks
    private RunnableTestCaseCounter counter;

    private final UUID datasetId = UUID.randomUUID();

    @Test
    @DisplayName("countRunnable delegates to the selector's conversation-granular unit count")
    void countRunnableDelegates() {
        List<UUID> disabled = List.of(UUID.randomUUID());
        String filterJson = "{\"op\":\"co\",\"args\":[]}";
        when(runnableTestCaseSelector.countRunnableUnits(datasetId, filterJson, disabled))
                .thenReturn(5L);

        assertThat(counter.countRunnable(datasetId, filterJson, disabled)).isEqualTo(5L);
    }

    @Test
    @DisplayName("countRunnable treats null disabled ids as an empty exclusion list")
    void countRunnableNullDisabled() {
        when(runnableTestCaseSelector.countRunnableUnits(eq(datasetId), eq(null), eq(List.of())))
                .thenReturn(2L);

        assertThat(counter.countRunnable(datasetId, null, null)).isEqualTo(2L);
    }
}
