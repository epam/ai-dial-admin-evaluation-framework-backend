package com.epam.aidial.evaluation.service.domain.dto.csv;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CsvImportPreviewDto {

    private List<CsvColumnInfoDto> detectedColumns;

    @Schema(description = "Number of CSV data rows parsed.", example = "3")
    private int totalRows;

    @Schema(
            description = "Number of test cases the CSV rows assemble into. Equals totalRows for a "
                    + "single-turn CSV; less than totalRows when the CSV contains multi-turn cases, whose "
                    + "turn rows assemble into one test case each.",
            example = "2")
    private int totalTestCases;

    private List<TestCaseResponseDto> sampleRows;
    private List<CsvImportWarningDto> warnings;

    /**
     * Auto-detected field definitions that would be persisted to the dataset's testCaseSchema
     * on import. Populated when the dataset schema is empty/null (or when MERGE adds new fields);
     * null when the existing dataset schema fully covers the CSV.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<FieldDefinitionDto> autoDetectedSchema;
}
