package com.epam.aidial.evaluation.data.db.analytics.model;

/** Suites classified healthy and failing in one week, {@code weekIndex} counted from the start of the range. */
public record WeeklySuiteHealth(int weekIndex, int healthyCount, int failingCount) {}
