package com.epam.aidial.evaluation.service.infrastructure.logger;

import com.epam.aidial.evaluation.service.infrastructure.dto.LoggerLevelsDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class LoggerConfigSourceJsonFile implements LoggerConfigSource {

    private final ObjectMapper objectMapper;
    private final String loggersPath;

    public LoggerLevelsDto readConfig() {
        var file = new File(loggersPath);
        if (file.exists()) {
            try {
                log.debug("Read configuration from file: {}", file);
                return objectMapper.readValue(file, LoggerLevelsDto.class);
            } catch (IOException e) {
                throw new IllegalStateException("Error reading config file: " + loggersPath, e);
            }
        }
        log.warn("Configuration directory {} doesn't exist", file);
        return new LoggerLevelsDto(Map.of());
    }
}
