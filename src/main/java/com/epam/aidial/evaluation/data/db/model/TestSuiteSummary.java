package com.epam.aidial.evaluation.data.db.model;

import java.util.UUID;

/**
 * Lightweight projection of a {@link TestSuite} carrying only the columns needed to identify a
 * suite ({@code id}, {@code name}, {@code description}). Used by the dataset → dependent-suites
 * listing to avoid fetching (and TOAST-decompressing) the suite's large JSONB columns.
 */
public record TestSuiteSummary(UUID id, String name, String description) {}
