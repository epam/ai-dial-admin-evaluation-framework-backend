package com.epam.aidial.evaluation.data.db.analytics.model;

/** Passed and total test cases of one run computation, as defined by the pass rule. */
public record PassCountTotals(int passed, int total) {}
