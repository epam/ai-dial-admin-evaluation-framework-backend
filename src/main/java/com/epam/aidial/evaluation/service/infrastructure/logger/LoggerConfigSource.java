package com.epam.aidial.evaluation.service.infrastructure.logger;

import com.epam.aidial.evaluation.service.infrastructure.dto.LoggerLevelsDto;

public interface LoggerConfigSource {
    LoggerLevelsDto readConfig();
}
