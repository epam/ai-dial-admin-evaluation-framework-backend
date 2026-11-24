package com.epam.aidial.evaluation.constants;

import lombok.experimental.UtilityClass;

/** Non-configurable constants of the analytics bounded context. */
@UtilityClass
public class AnalyticsConstants {

    /** Number of latest completed runs shown per suite in the run-trends endpoint. */
    public static final int RUN_TREND_LENGTH = 10;

    /** Length of one calendar week in UTC, in milliseconds; a {@code long} because 52 weeks overflow an int. */
    public static final long WEEK_MS = 7L * 86_400_000L;

    /** Weeks returned by the suite health distribution endpoint when the caller gives none. */
    public static final int HEALTH_DISTRIBUTION_DEFAULT_WEEKS = 8;

    /** Largest week count the suite health distribution endpoint accepts. */
    public static final int HEALTH_DISTRIBUTION_MAX_WEEKS = 52;

    /** Pass rate at or above which a suite counts as healthy when the caller gives no threshold. */
    public static final double HEALTH_DISTRIBUTION_DEFAULT_THRESHOLD = 0.75;
}
