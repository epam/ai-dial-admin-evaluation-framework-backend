package com.epam.aidial.evaluation.service.domain.dto.csv;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CsvColumnInfoDto {

    private String headerName;
    private String mappedTo;
    private String fieldName;
    private String inferredType;
}
