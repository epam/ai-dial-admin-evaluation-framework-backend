package com.epam.aidial.evaluation.data.db.analytics.model;

import java.util.UUID;

/** A (run, computation) pair that already has a pass-count row; {@code computationId} is null for the 0-of-0 row. */
public record RecordedComputation(UUID testSuiteRunId, UUID computationId) {}
