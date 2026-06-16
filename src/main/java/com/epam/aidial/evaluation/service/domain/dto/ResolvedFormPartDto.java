package com.epam.aidial.evaluation.service.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolvedFormPartDto {

    private String name;
    private FormPartType type;
    private Object resolvedValue;
    private String filename;
}
