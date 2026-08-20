package com.epam.aidial.evaluation.query.service.repository;

import java.util.List;
import java.util.Map;

/**
 * Result of executing a {@link com.epam.aidial.evaluation.query.model.StructuredQuery}:
 * the projected rows as ordered field-name → value maps (keys are the requested field names /
 * aggregate aliases), plus an optional {@code totalCount} populated only when offset paging requests
 * {@code include_total}. JSONB-backed columns surface as {@link org.jooq.JSONB} values.
 */
public record QueryResultPage(List<Map<String, Object>> rows, Long totalCount) {}
