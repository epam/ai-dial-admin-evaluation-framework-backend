package com.epam.aidial.evaluation.service.domain.csv;

import java.util.List;

/**
 * One completed contiguous run of CSV rows sharing a {@code testCaseName}: either a single-turn run (no row
 * carries a non-null {@code turnIndex}) or a multi-turn run (at least one row does). {@code firstRowNumber}
 * anchors warnings to the run's first CSV row, matching today's behavior. {@code nonContiguous} is set only
 * for a multi-turn run whose name was already seen in an earlier, already-completed multi-turn run —
 * non-contiguity is tracked for multi-turn runs only, never for single-turn runs of the same name.
 */
public record CsvTestCase(
        List<ParsedCsvRow> rows, String testCaseName, int firstRowNumber, boolean multiTurn, boolean nonContiguous) {}
