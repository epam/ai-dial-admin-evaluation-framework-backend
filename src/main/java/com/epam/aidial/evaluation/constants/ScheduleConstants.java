package com.epam.aidial.evaluation.constants;

/** Fixed values of the scheduled-run integration with the DIAL Core scheduler application. */
public final class ScheduleConstants {

    /** Scheduler target type EF always uses: a plain HTTP call. */
    public static final String TARGET_TYPE_REST = "rest";

    /** Key under which the run configuration is stored in the scheduler's request payload. */
    public static final String RUN_CONFIG_PAYLOAD_KEY = "runConfig";

    public static final String DEFAULT_TIMEZONE = "UTC";

    /**
     * Query parameter of a schedule's callback URL carrying the EF-generated reference that the run endpoint resolves
     * back to the schedule, so the run it creates can be tagged with the schedule id.
     */
    public static final String SCHEDULE_REF_PARAM = "scheduleRef";

    /** Scheduler fire status of a fire that reached its target; only such a fire can have started an EF run. */
    public static final String FIRE_STATUS_SUCCESS = "success";

    /** Most fire-detail calls to the scheduler in flight at once while enriching one page of fire history. */
    public static final int FIRE_DETAIL_CONCURRENCY = 4;

    /** Most finished fires whose extracted run id is remembered (a finished fire never changes). */
    public static final long FIRE_RUN_CACHE_MAX_SIZE = 10_000L;

    private ScheduleConstants() {}
}
