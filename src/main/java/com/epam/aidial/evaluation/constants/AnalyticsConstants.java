package com.epam.aidial.evaluation.constants;

import lombok.experimental.UtilityClass;

/** Non-configurable constants of the analytics bounded context. */
@UtilityClass
public class AnalyticsConstants {

    /** Number of latest completed runs shown per suite in the run-trends endpoint. */
    public static final int RUN_TREND_LENGTH = 10;
}
