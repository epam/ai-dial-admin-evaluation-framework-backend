package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.constants.AnalyticsConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.WeeklySuiteHealth;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.service.domain.TestSuiteService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuiteHealthDistributionDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuiteHealthWeekDto;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("SuiteHealthDistributionService")
class SuiteHealthDistributionServiceTest {

    // Wednesday 2026-11-18T12:00:00Z; the current week starts Monday 2026-11-16T00:00:00Z.
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
    private static final long CURRENT_WEEK_START =
            Instant.parse("2026-11-16T00:00:00Z").toEpochMilli();
    private static final long WEEK_MS = AnalyticsConstants.WEEK_MS;

    private RunPassCountService runPassCountService;
    private TestSuiteService testSuiteService;
    private FilterParser filterParser;
    private SuiteHealthDistributionService service;

    @BeforeEach
    void setUp() {
        runPassCountService = mock(RunPassCountService.class);
        testSuiteService = mock(TestSuiteService.class);
        filterParser = mock(FilterParser.class);
        when(filterParser.parse(any())).thenReturn(List.of());
        when(runPassCountService.getWeeklySuiteHealth(any(), anyLong(), anyLong(), anyDouble()))
                .thenReturn(List.of());
        service = new SuiteHealthDistributionService(
                runPassCountService,
                testSuiteService,
                filterParser,
                new HealthWeekRange(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    @DisplayName("weeks without counted suites are zero-filled, oldest first, with suiteCount the sum")
    void zeroFilledOldestFirst() {
        when(runPassCountService.getWeeklySuiteHealth(
                        isNull(),
                        Mockito.eq(CURRENT_WEEK_START - 2 * WEEK_MS),
                        Mockito.eq(CURRENT_WEEK_START + WEEK_MS),
                        Mockito.eq(0.5)))
                .thenReturn(List.of(new WeeklySuiteHealth(0, 3, 2), new WeeklySuiteHealth(2, 1, 0)));

        SuiteHealthDistributionDto result = service.getDistribution(3, 0.5, null);

        assertThat(result.getThreshold()).isEqualTo(0.5);
        assertThat(result.getWeeks())
                .containsExactly(
                        new SuiteHealthWeekDto(CURRENT_WEEK_START - 2 * WEEK_MS, 3, 2, 5),
                        new SuiteHealthWeekDto(CURRENT_WEEK_START - WEEK_MS, 0, 0, 0),
                        new SuiteHealthWeekDto(CURRENT_WEEK_START, 1, 0, 1));
    }

    @Test
    @DisplayName("a filter matching no suite gives zero weeks without an analytics read")
    void emptyFilterResult() {
        when(filterParser.parse(any())).thenReturn(List.of(mock(FilterCondition.class)));
        when(testSuiteService.findIds(any())).thenReturn(List.of());

        SuiteHealthDistributionDto result = service.getDistribution(2, 0.75, List.of("name:eq:x"));

        assertThat(result.getWeeks())
                .hasSize(2)
                .allSatisfy(w -> assertThat(w.getSuiteCount()).isZero());
        verify(runPassCountService, never()).getWeeklySuiteHealth(any(), anyLong(), anyLong(), anyDouble());
    }

    @Test
    @DisplayName("a filter passes the matching suite ids to the analytics read")
    void filterPassesIds() {
        UUID id = UUID.randomUUID();
        when(filterParser.parse(any())).thenReturn(List.of(mock(FilterCondition.class)));
        when(testSuiteService.findIds(any())).thenReturn(List.of(id));

        service.getDistribution(1, 0.75, List.of("name:eq:x"));

        verify(runPassCountService)
                .getWeeklySuiteHealth(Mockito.eq(List.of(id)), anyLong(), anyLong(), Mockito.eq(0.75));
    }

    @Test
    @DisplayName("no filter: TestSuiteService is never called and all suites are read")
    void noFilterNoMetaCall() {
        service.getDistribution(1, 0.75, null);

        verifyNoInteractions(testSuiteService);
        verify(runPassCountService).getWeeklySuiteHealth(isNull(), anyLong(), anyLong(), anyDouble());
    }
}
