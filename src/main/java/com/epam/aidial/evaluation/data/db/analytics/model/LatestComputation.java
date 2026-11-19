package com.epam.aidial.evaluation.data.db.analytics.model;

import java.util.UUID;

/** A run's latest computation (greatest {@code computed_at_ms}, ties by smaller id) and that timestamp. */
public record LatestComputation(UUID computationId, long computedAtMs) {}
