package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseEvalScore;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseEvalScoreRepository;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.dto.analytics.CursorPageResponseDto;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
 * Verifies that cursor-based pagination and JOIN correctness on the analytics list endpoints are
 * unaffected by native partitioning: a client walking the cursor chain sees an identical, complete,
 * gap-free sequence whether the underlying rows live in one partition or several, including at page
 * boundaries that coincide with partition boundaries.
 */
@DisplayName("Partitioned analytics pagination tests")
public abstract class PartitionedAnalyticsPaginationFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private TestCaseEvalScoreRepository testCaseEvalScoreRepository;

    private UUID suiteId;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupResults();
        analyticsTestDataHelper.cleanupEvalSummaries();
        suiteId = metaTestDataHelper
                .createTestSuite("Partitioned Pagination Suite")
                .getId();
    }

    @Test
    @DisplayName("test_case_run_results cursor pagination spans three monthly partitions with page boundaries "
            + "aligned to partition boundaries, with no duplicate or missing rows")
    void cursorPaginationSpansMultiplePartitions() {
        // The migration bootstraps _p_legacy (covers "now"), plus explicit partitions for the next two
        // look-ahead months — three genuinely distinct partitions, all already present without needing
        // the maintenance job to run.
        YearMonth currentMonth = YearMonth.from(Instant.now().atZone(ZoneOffset.UTC));
        long month0Ms = monthStartMs(currentMonth); // lands in _p_legacy
        long month1Ms = monthStartMs(currentMonth.plusMonths(1)); // lands in the first look-ahead partition
        long month2Ms = monthStartMs(currentMonth.plusMonths(2)); // lands in the second look-ahead partition

        List<UUID> expectedIdsNewestFirst = new ArrayList<>();
        // Insert oldest month first; each seedMonth call returns ids created-descending within that month.
        List<UUID> month0Ids = seedMonth(month0Ms, 3);
        List<UUID> month1Ids = seedMonth(month1Ms, 3);
        List<UUID> month2Ids = seedMonth(month2Ms, 3);
        expectedIdsNewestFirst.addAll(month2Ids);
        expectedIdsNewestFirst.addAll(month1Ids);
        expectedIdsNewestFirst.addAll(month0Ids);

        List<UUID> collectedIds = new ArrayList<>();
        String cursor = null;
        int pageCount = 0;
        boolean hasMore = true;
        while (hasMore) {
            String url = apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + suiteId + "&size=3"
                    + (cursor != null ? "&cursor=" + cursor : ""));
            var response = restTemplate.exchange(
                    url, HttpMethod.GET, null, new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            CursorPageResponseDto<Object> page = response.getBody();
            assertThat(page).isNotNull();
            assertThat(page.getContent()).hasSize(3);

            for (Object item : page.getContent()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) item;
                collectedIds.add(UUID.fromString((String) row.get("id")));
            }

            hasMore = page.isHasMore();
            cursor = page.getNextCursor();
            pageCount++;
            assertThat(pageCount).isLessThanOrEqualTo(3); // safety net against an infinite loop on a bug
        }

        assertThat(pageCount).isEqualTo(3);
        assertThat(collectedIds).hasSize(9);
        assertThat(new LinkedHashSet<>(collectedIds)).hasSize(9); // no duplicates across partition boundaries
        assertThat(collectedIds).containsExactlyElementsOf(expectedIdsNewestFirst);
    }

    @Test
    @DisplayName(
            "eval summaries and their scores read correctly regardless of which monthly partition a run's data lives in")
    void evalSummariesAndScoresCorrectAcrossDifferentPartitions() {
        YearMonth currentMonth = YearMonth.from(Instant.now().atZone(ZoneOffset.UTC));
        long[] monthsMs = {
            monthStartMs(currentMonth),
            monthStartMs(currentMonth.plusMonths(1)),
            monthStartMs(currentMonth.plusMonths(2))
        };

        for (long createdAtMs : monthsMs) {
            UUID runId = UUID.randomUUID();
            UUID computationId = UUID.randomUUID();
            UUID summaryId = analyticsTestDataHelper.createEvalSummary(
                    suiteId,
                    runId,
                    computationId,
                    "case-" + createdAtMs,
                    ExecutionStatus.SUCCESS.name(),
                    100L,
                    createdAtMs);
            testCaseEvalScoreRepository.saveAll(List.of(TestCaseEvalScore.builder()
                    .evalSummaryId(summaryId)
                    .score(0.75)
                    .passed(true)
                    .computedAtMs(createdAtMs)
                    .createdAtMs(createdAtMs)
                    .build()));

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
            assertThat(((Number) row.get("score")).doubleValue()).isEqualTo(0.75);
            assertThat(row.get("passed")).isEqualTo(true);
        }
    }

    /**
     * Seeds {@code count} run-result rows at distinct millisecond offsets within the same month,
     * returned newest-first (descending {@code created_at_ms}) to match the list endpoint's order.
     */
    private List<UUID> seedMonth(long monthStartMs, int count) {
        List<UUID> ids = new ArrayList<>(count);
        UUID runId = UUID.randomUUID();
        for (int i = count - 1; i >= 0; i--) {
            long createdAtMs = monthStartMs + i;
            UUID id = analyticsTestDataHelper.createTestRunResult(
                    runId, suiteId, UUID.randomUUID(), "case-" + createdAtMs, "{}", "{}", createdAtMs);
            ids.add(id);
        }
        return ids;
    }

    private static long monthStartMs(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
