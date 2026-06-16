package com.epam.aidial.evaluation.service.domain.dto.csv;

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
public class CsvImportResultDto {

    private int totalRows;
    private int validCount;
    private int invalidCount;
    private List<CsvImportWarningDto> warnings;

    /** Number of rows skipped due to name collision (null when conflictStrategy=FAIL). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer skippedCount;

    /** Number of rows that replaced an existing test case (null when conflictStrategy=FAIL). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer overriddenCount;
}
