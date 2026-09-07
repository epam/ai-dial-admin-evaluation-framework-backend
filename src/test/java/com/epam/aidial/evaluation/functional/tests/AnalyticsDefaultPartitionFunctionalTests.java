package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseEvalScore;
import com.epam.aidial.evaluation.data.db.analytics.repository.AnalyticsPartitionRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseEvalScoreRepository;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.dto.analytics.CursorPageResponseDto;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * Verifies that a write whose {@code created_at_ms} falls outside every explicitly created monthly
 * partition still succeeds (routed to {@code _p_default}) and is fully readable through the normal
 * REST list endpoints, for all three partitioned tables.
 */
@DisplayName("Analytics default partition fallback tests")
public abstract class AnalyticsDefaultPartitionFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private TestCaseEvalScoreRepository testCaseEvalScoreRepository;

    @Autowired
    private AnalyticsPartitionRepository partitionRepository;

    private UUID suiteId;

    /** Far enough in the future that no explicitly created monthly partition covers it. */
    private long farFutureCreatedAtMs;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupResults();
        analyticsTestDataHelper.cleanupEvalSummaries();
        suiteId = metaTestDataHelper
                .createTestSuite("Default Partition Suite " + UUID.randomUUID())
                .getId();
        farFutureCreatedAtMs = YearMonth.from(Instant.now().atZone(ZoneOffset.UTC))
                .plusYears(50)
                .atDay(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli();
    }

    @Test
    @DisplayName("a test_case_run_results row with a far-future created_at_ms lands in _p_default and is listable")
    void runResultOutOfRange_landsInDefaultAndIsListable() {
        UUID id = analyticsTestDataHelper.createTestRunResult(
                UUID.randomUUID(), suiteId, UUID.randomUUID(), "future-case", "{}", "{}", farFutureCreatedAtMs);

        assertThat(partitionRepository.countRows("test_case_run_results_p_default"))
                .isGreaterThanOrEqualTo(1L);

        var response = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + suiteId),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Object> content = response.getBody().getContent();
        assertThat(content).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) content.get(0);
        assertThat(row.get("id")).isEqualTo(id.toString());
    }

    @Test
    @DisplayName(
            "an eval summary with score, both with a far-future created_at_ms, land in _p_default and are readable together")
    void evalSummaryAndScoreOutOfRange_landInDefaultAndAreReadableTogether() {
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        UUID summaryId = analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "future-case",
                ExecutionStatus.SUCCESS.name(),
                100L,
                farFutureCreatedAtMs);
        testCaseEvalScoreRepository.saveAll(List.of(TestCaseEvalScore.builder()
                .evalSummaryId(summaryId)
                .score(0.42)
                .passed(false)
                .computedAtMs(farFutureCreatedAtMs)
                .createdAtMs(farFutureCreatedAtMs)
                .build()));

        assertThat(partitionRepository.countRows("test_case_eval_summaries_p_default"))
                .isGreaterThanOrEqualTo(1L);
        assertThat(partitionRepository.countRows("test_case_eval_scores_p_default"))
                .isGreaterThanOrEqualTo(1L);

        var response = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + runId + "&computation=" + computationId),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Object> content = response.getBody().getContent();
        assertThat(content).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) content.get(0);
        assertThat(row.get("id")).isEqualTo(summaryId.toString());
        assertThat(((Number) row.get("score")).doubleValue()).isEqualTo(0.42);
        assertThat(row.get("passed")).isEqualTo(false);
    }
}
