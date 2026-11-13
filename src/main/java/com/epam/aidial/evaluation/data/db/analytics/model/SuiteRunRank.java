package com.epam.aidial.evaluation.data.db.analytics.model;

import java.util.UUID;

/**
 * A run's position within its suite's scored runs: {@code rank} 1 is the newest, 2 the one before it.
 * {@code computationId} is the run's latest computation (newest {@code computed_at_ms}, ties on the smaller id).
 */
public record SuiteRunRank(UUID suiteId, UUID runId, UUID computationId, int rank) {}
