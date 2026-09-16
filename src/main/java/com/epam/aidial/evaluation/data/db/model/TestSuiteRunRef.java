package com.epam.aidial.evaluation.data.db.model;

import java.util.UUID;

/**
 * A minimal, selective-projection view of a {@code test_suite_runs} row: identity, status and creation
 * time, without {@code suite_snapshot} (the TOAST column the selective-projection pattern exists to avoid
 * on bulk reads). {@code status} stays a {@link String}, matching {@link TestSuiteRun#getStatus()}.
 */
public record TestSuiteRunRef(UUID id, String status, long createdAtMs) {}
