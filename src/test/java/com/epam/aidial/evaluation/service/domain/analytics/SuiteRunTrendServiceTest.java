package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.constants.AnalyticsConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.RunPassCount;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;
import com.epam.aidial.evaluation.service.domain.TestSuiteService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvaluateeDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuiteRunTrendDto;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@DisplayName("SuiteRunTrendService")
class SuiteRunTrendServiceTest {

    private static final PageRequest PAGE_REQUEST =
            PageRequest.builder().page(0).size(20).build();

    private RunPassCountService runPassCountService;
    private TestSuiteService testSuiteService;
    private SuiteEvaluateeMapper evaluateeMapper;
    private FilterParser filterParser;
    private SuiteRunTrendService service;

    @BeforeEach
    void setUp() {
        runPassCountService = mock(RunPassCountService.class);
        testSuiteService = mock(TestSuiteService.class);
        evaluateeMapper = mock(SuiteEvaluateeMapper.class);
        filterParser = mock(FilterParser.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(evaluateeMapper.toEvaluatee(any())).thenReturn(new EvaluateeDto());
        when(filterParser.parse(any())).thenReturn(List.of());
        service = new SuiteRunTrendService(
                runPassCountService,
                testSuiteService,
                evaluateeMapper,
                new PassRateCalculator(),
                filterParser,
                txManager);
    }

    private static RunPassCount row(UUID suiteId, long createdAt, int passed, int total) {
        return RunPassCount.builder()
                .testSuiteRunId(UUID.randomUUID())
                .testSuiteId(suiteId)
                .computationId(UUID.randomUUID())
                .runCreatedAtMs(createdAt)
                .passedCount(passed)
                .totalCount(total)
                .computedAtMs(createdAt)
                .build();
    }

    private static TestSuite suite(UUID id, String name) {
        return TestSuite.builder()
                .id(id)
                .name(name)
                .suiteType(SuiteType.DEPLOYMENT)
                .build();
    }

    private void stubPage(List<RunPassCount> latestRows) {
        when(runPassCountService.getLatestPage(any(), eq(PAGE_REQUEST)))
                .thenReturn(Page.of(latestRows, PAGE_REQUEST, latestRows.size()));
    }

    @Test
    @DisplayName("runs are returned oldest first; latest, previous and delta come from the two newest runs")
    void deltaAndOrder() {
        UUID suiteId = UUID.randomUUID();
        RunPassCount newest = row(suiteId, 3_000, 4, 4);
        RunPassCount middle = row(suiteId, 2_000, 3, 4);
        RunPassCount oldest = row(suiteId, 1_000, 2, 4);
        stubPage(List.of(newest));
        when(runPassCountService.getLatestRecordedRuns(anyCollection(), eq(AnalyticsConstants.RUN_TREND_LENGTH)))
                .thenReturn(Map.of(suiteId, List.of(newest, middle, oldest)));
        when(testSuiteService.findByIds(anyCollection())).thenReturn(List.of(suite(suiteId, "S")));

        PageResponseDto<SuiteRunTrendDto> result = service.getTrends(0, 20, null);

        assertThat(result.getTotalElements()).isEqualTo(1);
        SuiteRunTrendDto item = result.getContent().get(0);
        assertThat(item.getSuiteName()).isEqualTo("S");
        assertThat(item.getRuns()).extracting(r -> r.getCreatedAt()).containsExactly(1_000L, 2_000L, 3_000L);
        assertThat(item.getLatestPassRate()).isEqualTo(1.0);
        assertThat(item.getPreviousPassRate()).isEqualTo(0.75);
        assertThat(item.getDelta()).isEqualTo(0.25);
        verify(testSuiteService, never()).findIds(any());
    }

    @Test
    @DisplayName(
            "a latest run with total 0 has absent latest rate and delta; previous stays; a lone run has no previous")
    void absentRates() {
        UUID emptyLatest = UUID.randomUUID();
        UUID single = UUID.randomUUID();
        RunPassCount emptyRow = row(emptyLatest, 2_000, 0, 0);
        RunPassCount singleRow = row(single, 1_000, 2, 5);
        stubPage(List.of(singleRow, emptyRow));
        when(runPassCountService.getLatestRecordedRuns(anyCollection(), eq(AnalyticsConstants.RUN_TREND_LENGTH)))
                .thenReturn(Map.of(
                        emptyLatest, List.of(emptyRow, row(emptyLatest, 1_000, 9, 10)),
                        single, List.of(singleRow)));
        when(testSuiteService.findByIds(anyCollection()))
                .thenReturn(List.of(suite(emptyLatest, "E"), suite(single, "L")));

        List<SuiteRunTrendDto> content = service.getTrends(0, 20, null).getContent();

        assertThat(content).extracting(SuiteRunTrendDto::getSuiteName).containsExactly("L", "E");
        SuiteRunTrendDto lone = content.get(0);
        assertThat(lone.getLatestPassRate()).isEqualTo(0.4);
        assertThat(lone.getPreviousPassRate()).isNull();
        assertThat(lone.getDelta()).isNull();
        SuiteRunTrendDto empty = content.get(1);
        assertThat(empty.getLatestPassRate()).isNull();
        assertThat(empty.getPreviousPassRate()).isEqualTo(0.9);
        assertThat(empty.getDelta()).isNull();
        assertThat(empty.getRuns().get(1).getPassRate()).isNull();
    }

    @Test
    @DisplayName("a suite missing in meta is dropped from the page; the total is kept")
    void missingSuiteDropped() {
        UUID kept = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        RunPassCount keptRow = row(kept, 1_000, 1, 2);
        RunPassCount goneRow = row(gone, 1_000, 2, 2);
        stubPage(List.of(keptRow, goneRow));
        when(runPassCountService.getLatestRecordedRuns(anyCollection(), eq(AnalyticsConstants.RUN_TREND_LENGTH)))
                .thenReturn(Map.of(kept, List.of(keptRow), gone, List.of(goneRow)));
        when(testSuiteService.findByIds(anyCollection())).thenReturn(List.of(suite(kept, "K")));

        PageResponseDto<SuiteRunTrendDto> result = service.getTrends(0, 20, null);

        assertThat(result.getContent()).extracting(SuiteRunTrendDto::getSuiteId).containsExactly(kept);
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("a filter matching no suite gives an empty page without any analytics read")
    void emptyFilterResult() {
        List<FilterCondition> parsed = List.of(mock(FilterCondition.class));
        when(filterParser.parse(any())).thenReturn(parsed);
        when(testSuiteService.findIds(parsed)).thenReturn(List.of());

        PageResponseDto<SuiteRunTrendDto> result = service.getTrends(0, 20, List.of("suiteType eq \"MCP_TOOL\""));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        verify(runPassCountService, never()).getLatestPage(any(), any());
        verify(testSuiteService, never()).findByIds(any());
    }

    @Test
    @DisplayName("a filter restricts the analytics read to the matching suite ids")
    void filterRestrictsIds() {
        UUID id = UUID.randomUUID();
        List<FilterCondition> parsed = List.of(mock(FilterCondition.class));
        when(filterParser.parse(any())).thenReturn(parsed);
        when(testSuiteService.findIds(parsed)).thenReturn(List.of(id));
        when(runPassCountService.getLatestPage(eq(List.of(id)), eq(PAGE_REQUEST)))
                .thenReturn(Page.empty(PAGE_REQUEST));

        PageResponseDto<SuiteRunTrendDto> result = service.getTrends(0, 20, List.of("f"));

        assertThat(result.getContent()).isEmpty();
        verify(runPassCountService).getLatestPage(List.of(id), PAGE_REQUEST);
        verify(testSuiteService, never()).findByIds(any());
    }
}
