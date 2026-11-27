package com.epam.aidial.evaluation.data.db.model;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Link between a test suite and a schedule stored in the DIAL Core scheduler application.
 * The scheduler id is an opaque string.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestSuiteSchedule {
    private String scheduleId;
    private UUID testSuiteId;
    private String createdBy;
    private Long createdAt;

    /**
     * EF-generated reference carried in the schedule's callback URL ({@code ?scheduleRef=...}) and resolved back to
     * this schedule when the scheduler fires. Null for schedules created before run tagging existed.
     */
    private String scheduleRef;
}
