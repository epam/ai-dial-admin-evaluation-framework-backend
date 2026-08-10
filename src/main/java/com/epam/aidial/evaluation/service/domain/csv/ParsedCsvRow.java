package com.epam.aidial.evaluation.service.domain.csv;

import java.util.Map;

/**
 * One parsed CSV data row: its 1-based CSV row number, resolved {@code testCaseName}, the reserved
 * {@code turnIndex} ordering hint ({@code null} for a single-turn row or an unparseable cell), the row's
 * {@code data} columns, and whether any OBJECT/ARRAY cell failed JSON parsing. Public because both
 * {@code CsvImportService} (in {@code service.domain}) and the run-grouping/assembly components added in
 * later groups (in this package) need it.
 */
public record ParsedCsvRow(
        int rowNumber, String testCaseName, Integer turnIndex, Map<String, Object> data, boolean hasJsonParseErrors) {}
