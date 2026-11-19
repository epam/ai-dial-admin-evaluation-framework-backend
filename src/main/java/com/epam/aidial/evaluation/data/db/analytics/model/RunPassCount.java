package com.epam.aidial.evaluation.data.db.analytics.model;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stored pass counts of one completed run for one computation. {@code computationId} is null only for a run
 * without eval summaries (0 of 0, {@code computedAtMs} 0), which ranks below any real computation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunPassCount {
    private UUID testSuiteRunId;
    private UUID testSuiteId;
    private UUID computationId;
    private long runCreatedAtMs;
    private int passedCount;
    private int totalCount;
    private long computedAtMs;
}
