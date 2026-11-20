package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.constants.AnalyticsConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCount;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.TestSuiteService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunTrendPointDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuiteRunTrendDto;
import com.epam.aidial.evaluation.service.domain.dto.page.PageResponseMapper;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Suites ranked worst-first by the pass rate of their latest completed run, each with the trend of its latest
 * runs.
 *
 * <p>Two datasources, no nesting: an optional suite filter is resolved in meta first; then one read-only
 * analytics transaction reads the sorted page and the trends of the page's suites; finally the page's suites are
 * read from meta in one bulk query, after that transaction has completed. A suite that no longer exists in meta
 * is dropped from the page.
 */
@Slf4j
@Service
@LogExecution
public class SuiteRunTrendService {

    private final RunPassCountService runPassCountService;
    private final TestSuiteService testSuiteService;
    private final SuiteEvaluateeMapper suiteEvaluateeMapper;
    private final PassRateCalculator passRateCalculator;
    private final FilterParser filterParser;
    private final TransactionTemplate analyticsTransactionTemplate;

    public SuiteRunTrendService(
            RunPassCountService runPassCountService,
            TestSuiteService testSuiteService,
            SuiteEvaluateeMapper suiteEvaluateeMapper,
            PassRateCalculator passRateCalculator,
            FilterParser filterParser,
            @Qualifier("analyticsTransactionManager") PlatformTransactionManager analyticsTxManager) {
        this.runPassCountService = runPassCountService;
        this.testSuiteService = testSuiteService;
        this.suiteEvaluateeMapper = suiteEvaluateeMapper;
        this.passRateCalculator = passRateCalculator;
        this.filterParser = filterParser;
        this.analyticsTransactionTemplate = new TransactionTemplate(analyticsTxManager);
        this.analyticsTransactionTemplate.setReadOnly(true);
    }

    /**
     * @param filter raw suite filter expressions (same as the test suite list); null or empty means all suites
     * @throws FilterValidationException for an invalid filter
     */
    public PageResponseDto<SuiteRunTrendDto> getTrends(int page, int size, List<String> filter) {
        final PageRequest pageRequest =
                PageRequest.builder().page(page).size(size).build();
        return PageResponseMapper.from(getTrendsPage(filter, pageRequest));
    }

    private Page<SuiteRunTrendDto> getTrendsPage(List<String> filter, PageRequest pageRequest) {
        final List<FilterCondition> filters = filterParser.parse(filter != null ? filter : List.of());
        final List<UUID> suiteIds = filters.isEmpty() ? null : testSuiteService.findIds(filters);
        if (suiteIds != null && suiteIds.isEmpty()) {
            return Page.empty(pageRequest);
        }

        final AnalyticsPage analyticsPage =
                analyticsTransactionTemplate.execute(status -> readAnalytics(suiteIds, pageRequest));

        final List<UUID> pageSuiteIds = analyticsPage.page().getContent().stream()
                .map(RunPassCount::getTestSuiteId)
                .toList();
        final Map<UUID, TestSuite> suitesById = pageSuiteIds.isEmpty()
                ? Map.of()
                : testSuiteService.findByIds(pageSuiteIds).stream()
                        .collect(Collectors.toMap(TestSuite::getId, Function.identity()));

        final List<SuiteRunTrendDto> items = pageSuiteIds.stream()
                .map(suiteId -> toItem(
                        suitesById.get(suiteId), analyticsPage.trendsBySuite().get(suiteId)))
                .filter(Objects::nonNull)
                .toList();
        final Page<RunPassCount> source = analyticsPage.page();
        return Page.<SuiteRunTrendDto>builder()
                .content(items)
                .page(source.getPage())
                .size(source.getSize())
                .totalElements(source.getTotalElements())
                .totalPages(source.getTotalPages())
                .hasNext(source.isHasNext())
                .hasPrevious(source.isHasPrevious())
                .build();
    }

    private AnalyticsPage readAnalytics(List<UUID> suiteIds, PageRequest pageRequest) {
        final Page<RunPassCount> page = runPassCountService.getLatestPage(suiteIds, pageRequest);
        final List<UUID> pageSuiteIds =
                page.getContent().stream().map(RunPassCount::getTestSuiteId).toList();
        final Map<UUID, List<RunPassCount>> trends = pageSuiteIds.isEmpty()
                ? Map.of()
                : runPassCountService.getLatestRecordedRuns(pageSuiteIds, AnalyticsConstants.RUN_TREND_LENGTH);
        return new AnalyticsPage(page, trends);
    }

    private SuiteRunTrendDto toItem(TestSuite suite, List<RunPassCount> newestFirst) {
        if (suite == null || newestFirst == null || newestFirst.isEmpty()) {
            return null;
        }
        final Double latest = rate(newestFirst.get(0));
        final Double previous = newestFirst.size() > 1 ? rate(newestFirst.get(1)) : null;
        final List<RunPassCount> oldestFirst = newestFirst.reversed();
        return SuiteRunTrendDto.builder()
                .suiteId(suite.getId())
                .suiteName(suite.getName())
                .suiteType(suite.getSuiteType())
                .evaluatee(suiteEvaluateeMapper.toEvaluatee(suite))
                .runs(oldestFirst.stream().map(this::toPoint).toList())
                .latestPassRate(latest)
                .previousPassRate(previous)
                .delta(latest != null && previous != null ? latest - previous : null)
                .build();
    }

    private RunTrendPointDto toPoint(RunPassCount row) {
        return RunTrendPointDto.builder()
                .runId(row.getTestSuiteRunId())
                .createdAt(row.getRunCreatedAtMs())
                .passRate(rate(row))
                .passedCount(row.getPassedCount())
                .totalCount(row.getTotalCount())
                .build();
    }

    private Double rate(RunPassCount row) {
        return passRateCalculator.rate(row.getPassedCount(), row.getTotalCount());
    }

    private record AnalyticsPage(Page<RunPassCount> page, Map<UUID, List<RunPassCount>> trendsBySuite) {}
}
