package com.epam.aidial.evaluation.data.db.model;

import java.util.Set;

public enum RunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    private static final Set<String> TERMINAL_STATUSES = Set.of(COMPLETED.name(), FAILED.name(), CANCELLED.name());

    public static boolean isTerminal(String status) {
        return TERMINAL_STATUSES.contains(status);
    }
}
