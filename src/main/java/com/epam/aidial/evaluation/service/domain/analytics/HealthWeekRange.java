package com.epam.aidial.evaluation.service.domain.analytics;

import static java.time.DayOfWeek.MONDAY;

import com.epam.aidial.evaluation.constants.AnalyticsConstants;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Week arithmetic of the suite health distribution. The current week start comes from the calendar (the Monday on
 * or before today in UTC), not from flooring epoch milliseconds, which would land on Thursdays. UTC has no daylight
 * saving, so every week is exactly {@link AnalyticsConstants#WEEK_MS} long.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class HealthWeekRange {

    private final Clock clock;

    /** The range of the current week and the {@code weeks - 1} weeks before it. */
    public WeekRange range(int weeks) {
        final LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        final long currentWeekStart = today.with(TemporalAdjusters.previousOrSame(MONDAY))
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli();
        final long startMs = currentWeekStart - (weeks - 1L) * AnalyticsConstants.WEEK_MS;
        final List<Long> weekStarts = new ArrayList<>(weeks);
        for (int i = 0; i < weeks; i++) {
            weekStarts.add(startMs + i * AnalyticsConstants.WEEK_MS);
        }
        return new WeekRange(startMs, currentWeekStart + AnalyticsConstants.WEEK_MS, List.copyOf(weekStarts));
    }
}
