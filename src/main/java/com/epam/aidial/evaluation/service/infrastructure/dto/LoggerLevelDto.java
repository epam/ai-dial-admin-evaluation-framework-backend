package com.epam.aidial.evaluation.service.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoggerLevelDto {
    private String defaultLevel;
    private String configuredLevel;
    private Long validTill;
}
