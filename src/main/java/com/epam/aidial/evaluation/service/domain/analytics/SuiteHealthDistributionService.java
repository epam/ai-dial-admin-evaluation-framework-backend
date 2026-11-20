package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.data.db.analytics.model.WeeklySuiteHealth;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.TestSuiteService;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuiteHealthDistributionDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.SuiteHealthWeekDto;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Week by week counts of healthy and failing suites. An optional suite filter is resolved in meta first; the
 * pass counts are then read from analytics in one statement. No transaction is opened here, so a meta read never
 * runs inside an analytics transaction.
 */
@Service
@LogExecution
@RequiredArgsConstructor
public class SuiteHealthDistributionService {

    private final RunPassCountService runPassCountService;
    private final TestSuiteService testSuiteService;
    private final FilterParser filterParser;
    private final HealthWeekRange healthWeekRange;

    /**
     * @param filter raw suite filter expressions (same as the test suite list); null or empty means all suites
     * @throws FilterValidationException for an invalid filter
     */
    public SuiteHealthDistributionDto getDistribution(int weeks, double threshold, List<String> filter) {
        final List<FilterCondition> filters = filterParser.parse(filter != null ? filter : List.of());
        final List<UUID> suiteIds = filters.isEmpty() ? null : testSuiteService.findIds(filters);
        final WeekRange range = healthWeekRange.range(weeks);
        final Map<Integer, WeeklySuiteHealth> byWeek = suiteIds != null && suiteIds.isEmpty()
                ? Map.of()
                : runPassCountService.getWeeklySuiteHealth(suiteIds, range.startMs(), range.endMs(), threshold).stream()
                        .collect(Collectors.toMap(WeeklySuiteHealth::weekIndex, Function.identity()));
        final List<SuiteHealthWeekDto> entries = IntStream.range(
                        0, range.weekStarts().size())
                .mapToObj(i -> toEntry(range.weekStarts().get(i), byWeek.get(i)))
                .toList();
        return SuiteHealthDistributionDto.builder()
                .threshold(threshold)
                .weeks(entries)
                .build();
    }

    private static SuiteHealthWeekDto toEntry(long weekStart, WeeklySuiteHealth health) {
        final int healthy = health != null ? health.healthyCount() : 0;
        final int failing = health != null ? health.failingCount() : 0;
        return SuiteHealthWeekDto.builder()
                .weekStart(weekStart)
                .healthyCount(healthy)
                .failingCount(failing)
                .suiteCount(healthy + failing)
                .build();
    }
}
