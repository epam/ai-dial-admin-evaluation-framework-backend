package com.epam.aidial.evaluation.service.domain.csv;

import java.util.List;
import java.util.Map;

/**
 * The assembled shape of a multi-turn {@link CsvTestCase}: the case's shared (test-case-level) data map, its
 * ordered per-turn maps, and the conflicts detected while assembling — a shared-column mismatch across the
 * run's rows, a duplicate {@code turnIndex} within the run, and whether any row already carried a
 * JSON-parse failure. Conflicts are returned as data, not warnings: the caller decides how to render or
 * persist them.
 */
public record MultiTurnAssembly(
        Map<String, Object> sharedData,
        List<Map<String, Object>> perTurnMaps,
        boolean sharedConflict,
        boolean duplicateTurnIndex,
        boolean hasJsonParseErrors) {}
