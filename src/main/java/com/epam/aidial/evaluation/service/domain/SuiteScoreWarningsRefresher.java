package com.epam.aidial.evaluation.service.domain;

import static com.epam.aidial.evaluation.service.domain.ScoreReferenceValidator.OVERALL_SCORE_ROOT;
import static com.epam.aidial.evaluation.service.domain.ScoreReferenceValidator.TEST_CASE_OVERALL_SCORE_ROOT;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.data.db.transaction.timestamp.TransactionTimestampContext;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Replaces only the score-reference warnings (path under {@code $.overallScore.} or
 * {@code $.testCaseOverallScore.}) of a suite, keeping every other stored warning. Must be called from a
 * {@code @Transactional} caller so the transaction timestamp is available. Writes nothing when neither the
 * warnings nor the validity change.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class SuiteScoreWarningsRefresher {

    private static final String OVERALL_SCORE_PREFIX = OVERALL_SCORE_ROOT + ".";
    private static final String TEST_CASE_OVERALL_SCORE_PREFIX = TEST_CASE_OVERALL_SCORE_ROOT + ".";

    private final TestSuiteRepository testSuiteRepository;
    private final ValidationWarningsSerializer warningsSerializer;
    private final ScoreReferenceValidator scoreReferenceValidator;
    private final TransactionTimestampContext transactionTimestampContext;

    public void refresh(TestSuite suite, Set<String> metricNames) {
        final List<ValidationWarningDto> stored = warningsSerializer.deserializeWarnings(suite.getValidationWarnings());
        final List<ValidationWarningDto> refreshed = new ArrayList<>(
                stored.stream().filter(warning -> !isScoreWarning(warning)).toList());
        refreshed.addAll(scoreReferenceValidator.validate(suite, metricNames));
        final boolean valid = refreshed.isEmpty();
        if (refreshed.equals(stored) && valid == suite.isValid()) {
            return;
        }
        log.info("Refreshing score warnings of suite {}: {} warning(s)", suite.getId(), refreshed.size());
        testSuiteRepository.updateValidation(
                suite.getId(),
                valid,
                warningsSerializer.serializeWarnings(refreshed),
                transactionTimestampContext.getTimestamp());
    }

    private static boolean isScoreWarning(ValidationWarningDto warning) {
        final String path = warning.getPath();
        return path != null
                && (path.startsWith(OVERALL_SCORE_PREFIX) || path.startsWith(TEST_CASE_OVERALL_SCORE_PREFIX));
    }
}
