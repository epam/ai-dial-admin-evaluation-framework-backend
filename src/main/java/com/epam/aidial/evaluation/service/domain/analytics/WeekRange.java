package com.epam.aidial.evaluation.service.domain.analytics;

import java.util.List;

/**
 * UTC week window: {@code startMs} is Monday 00:00 of the oldest week, {@code endMs} the first millisecond after the
 * current week (exclusive), {@code weekStarts} the Monday starts, oldest first.
 */
public record WeekRange(long startMs, long endMs, List<Long> weekStarts) {}
