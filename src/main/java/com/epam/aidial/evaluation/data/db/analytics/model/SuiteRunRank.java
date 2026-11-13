package com.epam.aidial.evaluation.data.db.analytics.model;

import java.util.UUID;

/** A run's position within its suite's scored runs: {@code rank} 1 is the newest, 2 the one before it. */
public record SuiteRunRank(UUID suiteId, UUID runId, int rank) {}
