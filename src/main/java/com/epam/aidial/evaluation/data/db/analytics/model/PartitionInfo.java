package com.epam.aidial.evaluation.data.db.analytics.model;

/**
 * A partition of one of the partitioned analytics parent tables. {@code lowerBoundMs} is
 * {@code null} for the legacy partition (unbounded {@code MINVALUE} lower bound) and for the
 * DEFAULT partition; {@code upperBoundMs} is {@code null} only for the DEFAULT partition.
 */
public record PartitionInfo(String name, Long lowerBoundMs, Long upperBoundMs, boolean isDefault) {}
