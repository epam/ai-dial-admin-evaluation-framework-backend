package com.epam.aidial.evaluation.service.domain.dto.csv;

import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.fasterxml.jackson.annotation.JsonInclude;
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
    private int totalRows;
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
