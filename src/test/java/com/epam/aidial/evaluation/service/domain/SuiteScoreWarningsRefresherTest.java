package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SuiteScoreWarningsRefresherTest {

    private static final long TIMESTAMP = 1234L;
    private static final String STORED_JSON = "stored";
    private static final Set<String> NAMES = Set.of("Accuracy");

    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private ValidationWarningsSerializer warningsSerializer;

    @Mock
    private ScoreReferenceValidator scoreReferenceValidator;

    @Mock
    private TransactionTimestampContext transactionTimestampContext;

    private SuiteScoreWarningsRefresher refresher() {
        return new SuiteScoreWarningsRefresher(
                testSuiteRepository, warningsSerializer, scoreReferenceValidator, transactionTimestampContext);
    }

    private static ValidationWarningDto warning(String path) {
        return ValidationWarningDto.builder()
                .path(path)
                .fieldName("f")
                .message("m")
                .code(ValidationWarningCode.UNRESOLVED_REFERENCE)
                .build();
    }

    private static TestSuite suite(boolean valid) {
        return TestSuite.builder()
                .id(UUID.randomUUID())
                .valid(valid)
                .validationWarnings(STORED_JSON)
                .build();
    }

    @Test
    @DisplayName("Keeps non-score warnings, replaces score warnings and recomputes valid")
    void keepsNonScoreWarningsAndReplacesScoreOnes() {
        final TestSuite suite = suite(false);
        final ValidationWarningDto binding = warning("$.inputBindings[0]");
        final ValidationWarningDto stale = warning("$.overallScore.weights[0].metricName");
        final ValidationWarningDto staleTestCase = warning("$.testCaseOverallScore.weights[0].metricName");
        final ValidationWarningDto fresh = warning("$.overallScore.weights[1].metricName");
        when(warningsSerializer.deserializeWarnings(STORED_JSON)).thenReturn(List.of(binding, stale, staleTestCase));
        when(scoreReferenceValidator.validate(suite, NAMES)).thenReturn(List.of(fresh));
        when(warningsSerializer.serializeWarnings(any())).thenReturn("new");
        when(transactionTimestampContext.getTimestamp()).thenReturn(TIMESTAMP);

        refresher().refresh(suite, NAMES);

        final ArgumentCaptor<List<ValidationWarningDto>> captor = ArgumentCaptor.captor();
        verify(warningsSerializer).serializeWarnings(captor.capture());
        assertThat(captor.getValue()).containsExactly(binding, fresh);
        verify(testSuiteRepository).updateValidation(suite.getId(), false, "new", TIMESTAMP);
    }

    @Test
    @DisplayName("Marks suite valid when the last warning is resolved")
    void recomputesValidToTrueWhenNoWarningsRemain() {
        final TestSuite suite = suite(false);
        when(warningsSerializer.deserializeWarnings(STORED_JSON))
                .thenReturn(List.of(warning("$.overallScore.weights[0].metricName")));
        when(scoreReferenceValidator.validate(suite, NAMES)).thenReturn(List.of());
        when(warningsSerializer.serializeWarnings(List.of())).thenReturn("[]");
        when(transactionTimestampContext.getTimestamp()).thenReturn(TIMESTAMP);

        refresher().refresh(suite, NAMES);

        verify(testSuiteRepository).updateValidation(suite.getId(), true, "[]", TIMESTAMP);
    }

    @Test
    @DisplayName("Does not write when warnings and validity are unchanged")
    void noWriteWhenUnchanged() {
        final TestSuite suite = suite(false);
        final ValidationWarningDto binding = warning("$.inputBindings[0]");
        final ValidationWarningDto score = warning("$.overallScore.weights[0].metricName");
        when(warningsSerializer.deserializeWarnings(STORED_JSON)).thenReturn(List.of(binding, score));
        when(scoreReferenceValidator.validate(suite, NAMES)).thenReturn(List.of(warning(score.getPath())));

        refresher().refresh(suite, NAMES);

        verify(testSuiteRepository, never()).updateValidation(any(), anyBoolean(), anyString(), anyLong());
    }

    @Test
    @DisplayName("Does not treat $.overallScoreThreshold warnings as score-reference warnings")
    void prefixDoesNotMatchOverallScoreThreshold() {
        final TestSuite suite = suite(false);
        final ValidationWarningDto threshold = warning("$.overallScoreThreshold");
        when(warningsSerializer.deserializeWarnings(STORED_JSON)).thenReturn(List.of(threshold));
        when(scoreReferenceValidator.validate(suite, NAMES)).thenReturn(List.of());

        refresher().refresh(suite, NAMES);

        verify(testSuiteRepository, never()).updateValidation(any(), anyBoolean(), anyString(), anyLong());
        verify(warningsSerializer, never()).serializeWarnings(any());
    }
}
