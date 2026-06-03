package com.epam.aidial.evaluation.service.infrastructure.dto;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoggerLevelsDto {
    private Map<String, LoggerLevelDto> loggerLevels;
}
