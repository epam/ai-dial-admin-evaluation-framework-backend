package com.epam.aidial.evaluation.service.domain.dto.csv;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Controls what happens when a {@code testCaseName} collision occurs —
 * either a CSV row name matching an existing test case or a duplicate name within the CSV itself
 * (applies to all import modes).
 * <ul>
 *   <li>FAIL     – abort with HTTP 409 on first collision (default)</li>
 *   <li>SKIP     – silently skip the colliding row (first wins)</li>
 *   <li>OVERRIDE – replace the existing row (last wins)</li>
 * </ul>
 */
@Schema(description = "Controls what happens when a testCaseName collision occurs during CSV import")
public enum CsvConflictStrategy {
    @Schema(description = "Abort with HTTP 409 on first name collision")
    FAIL,
    @Schema(description = "Silently skip colliding rows, first wins")
    SKIP,
    @Schema(description = "Replace existing rows with colliding names, last wins")
    OVERRIDE
}
