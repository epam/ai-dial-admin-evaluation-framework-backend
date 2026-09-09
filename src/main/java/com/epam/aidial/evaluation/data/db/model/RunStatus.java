package com.epam.aidial.evaluation.data.db.model;

import java.util.List;
import java.util.Set;

public enum RunStatus {
    PENDING,
    RUNNING,
    CANCELLING,
    COMPLETED,
    FAILED,
    CANCELLED;

    private static final Set<String> TERMINAL_STATUSES = Set.of(COMPLETED.name(), FAILED.name(), CANCELLED.name());

    /**
     * Statuses under which a run still holds execution resources (or is waiting to): PENDING (not yet
     * dispatched), RUNNING (executing), and CANCELLING (cancellation requested, async job has not yet
     * finalized it). Used by concurrency-limit enforcement so a CANCELLING run still counts against the
     * global/per-suite active-run caps until it reaches a terminal status.
     */
    public static final Set<RunStatus> ACTIVE_STATUSES = Set.of(PENDING, RUNNING, CANCELLING);

    public static boolean isTerminal(String status) {
        return TERMINAL_STATUSES.contains(status);
    }

    /** {@link #ACTIVE_STATUSES} as their {@code name()} strings, for repository queries. */
    public static List<String> activeStatusNames() {
        return ACTIVE_STATUSES.stream().map(RunStatus::name).toList();
    }
}
