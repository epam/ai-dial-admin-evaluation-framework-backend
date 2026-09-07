package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.infrastructure.partition.MonthlyPartitionBounds;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Advisory check that a run-scoped query against {@code test_case_eval_summaries} — filtering on
 * {@code created_at_ms = <the run's timestamp>}, the same equality predicate
 * {@code PostgresEvalSummaryRepository.buildBaseCondition} applies whenever the caller supplies
 * {@code runCreatedAtMs} — prunes to a bounded number of partitions rather than scanning every one.
 * This is a containment assertion on the query plan text, not exact plan-string matching, since
 * plan shape is version- and statistics-sensitive; deeper pruning validation (real data volumes,
 * {@code EXPLAIN ANALYZE} timings) belongs in an operational runbook, not CI.
 */
@DisplayName("Analytics partition pruning tests")
public abstract class AnalyticsPartitionPruningFunctionalTests extends BaseFunctionalTest {

    private static final Pattern PARTITION_NAME_PATTERN = Pattern.compile("test_case_eval_summaries_p\\w+");

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    @Qualifier("analyticsRawJdbcTemplate")
    private JdbcTemplate analyticsRawJdbcTemplate;

    private UUID suiteId;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupEvalSummaries();
        suiteId = metaTestDataHelper
                .createTestSuite("Pruning Suite " + UUID.randomUUID())
                .getId();
    }

    @Test
    @DisplayName("a run-scoped query (created_at_ms pinned to one run) touches at most a bounded number of partitions")
    void runScopedQuery_prunesToBoundedPartitionCount() {
        YearMonth currentMonth = YearMonth.from(Instant.now().atZone(ZoneOffset.UTC));
        long targetRunCreatedAtMs = MonthlyPartitionBounds.lowerBoundMs(currentMonth.plusMonths(1));
        UUID targetRunId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                targetRunId,
                UUID.randomUUID(),
                "case-target",
                ExecutionStatus.SUCCESS.name(),
                100L,
                targetRunCreatedAtMs);

        // Seed unrelated data in two other months so an unpruned scan would visibly touch more partitions.
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "case-other-1",
                ExecutionStatus.SUCCESS.name(),
                100L,
                MonthlyPartitionBounds.lowerBoundMs(currentMonth));
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "case-other-2",
                ExecutionStatus.SUCCESS.name(),
                100L,
                MonthlyPartitionBounds.lowerBoundMs(currentMonth.plusMonths(2)));

        List<String> planLines = analyticsRawJdbcTemplate.queryForList(
                "EXPLAIN (COSTS OFF) SELECT * FROM test_case_eval_summaries "
                        + "WHERE test_suite_run_id = ? AND created_at_ms = ?",
                String.class,
                targetRunId.toString(),
                targetRunCreatedAtMs);
        String plan = String.join("\n", planLines);

        Set<String> touchedPartitions = new LinkedHashSet<>();
        Matcher matcher = PARTITION_NAME_PATTERN.matcher(plan);
        while (matcher.find()) {
            touchedPartitions.add(matcher.group());
        }

        assertThat(touchedPartitions)
                .as(
                        "query plan should reference at most the target month's partition plus the default; full plan:%n%s",
                        plan)
                .hasSizeLessThanOrEqualTo(2);
    }
}
