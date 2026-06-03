package com.epam.aidial.evaluation.service.domain.dto.csv;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CsvImportWarningDto {

    private int rowNumber;
    private String columnName;
    private String message;
}
