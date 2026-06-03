package com.epam.aidial.evaluation.service.domain.dto.csv;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Controls which rows are affected during CSV import.
 * <ul>
 *   <li>OVERRIDE – delete all existing test cases first, then insert all CSV rows</li>
 *   <li>APPEND  – keep existing test cases, insert only new rows</li>
 *   <li>MERGE   – keep existing test cases, insert new rows and add new schema fields</li>
 * </ul>
 */
@Schema(description = "Controls which rows are affected during CSV import")
public enum CsvImportMode {
    @Schema(description = "Delete all existing test cases, then insert all CSV rows")
    OVERRIDE,
    @Schema(description = "Keep existing test cases, insert only new rows")
    APPEND,
    @Schema(description = "Keep existing test cases, insert new rows and add new schema fields")
    MERGE
}
