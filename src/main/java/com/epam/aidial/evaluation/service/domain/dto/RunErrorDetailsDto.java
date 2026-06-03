package com.epam.aidial.evaluation.service.domain.dto;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunErrorDetailsDto {

    private String code;
    private String category;
    private String message;
    private Map<String, Object> details;
}
