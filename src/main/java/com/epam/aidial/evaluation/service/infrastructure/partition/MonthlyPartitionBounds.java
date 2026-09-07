package com.epam.aidial.evaluation.service.infrastructure.partition;

import com.epam.aidial.evaluation.constants.AnalyticsPartitioningConstants;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure UTC month-bound arithmetic for the analytics partition-maintenance job: partition naming,
 * lower/upper `created_at_ms` bounds for a given month, which months need a partition to exist,
 * and whether a given month's partition has aged past a retention horizon.
 *
 * <p>No Spring dependency — this is the unit-testable core of {@code AnalyticsPartitionMaintenanceService}.
 */
public final class MonthlyPartitionBounds {

    private MonthlyPartitionBounds() {}

    public static String partitionName(String tableName, YearMonth month) {
        return tableName
                + AnalyticsPartitioningConstants.PARTITION_PREFIX
                + month.format(AnalyticsPartitioningConstants.MONTH_LABEL_FORMAT);
    }

    public static long lowerBoundMs(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    public static long upperBoundMs(YearMonth month) {
        return lowerBoundMs(month.plusMonths(1));
    }

    /**
     * The current UTC month plus each of the next {@code lookAheadMonths} months
     * ({@code lookAheadMonths + 1} months total), in ascending order.
     */
    public static List<YearMonth> monthsToEnsure(Instant now, int lookAheadMonths) {
        YearMonth currentMonth = currentUtcMonth(now);
        List<YearMonth> months = new ArrayList<>(lookAheadMonths + 1);
        for (int i = 0; i <= lookAheadMonths; i++) {
            months.add(currentMonth.plusMonths(i));
        }
        return months;
    }

    /**
     * {@code true} when {@code partitionMonth}'s entire range is older than {@code retentionMonths}
     * months before the current UTC month, i.e. its upper bound is at or before the retention horizon.
     *
     * <p>{@code retentionMonths <= 0} always returns {@code false} — retention is a disabled/opt-in
     * sentinel, not a zero-width horizon, so no partition is ever reported expired in that case.
     */
    public static boolean expiredBefore(YearMonth partitionMonth, Instant now, int retentionMonths) {
        if (retentionMonths <= 0) {
            return false;
        }
        YearMonth horizonMonth = currentUtcMonth(now).minusMonths(retentionMonths);
        return upperBoundMs(partitionMonth) <= lowerBoundMs(horizonMonth);
    }

    public static YearMonth currentUtcMonth(Instant now) {
        return YearMonth.from(now.atZone(ZoneOffset.UTC));
    }
}
