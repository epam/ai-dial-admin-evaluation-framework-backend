package com.epam.aidial.evaluation.configuration.logging;

import com.epam.aidial.evaluation.configuration.properties.logging.LoggerConfigProperties;
import com.epam.aidial.evaluation.service.infrastructure.logger.ConfigUpdaterLoggerLevel;
import com.epam.aidial.evaluation.service.infrastructure.logger.LoggerConfigSource;
import com.epam.aidial.evaluation.service.infrastructure.logger.LoggerConfigSourceJsonFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LoggerConfig {

    @Bean
    public LoggerConfigSource configSourcePropertyFile(
            ObjectMapper objectMapper, LoggerConfigProperties configClientProperties) {
        return new LoggerConfigSourceJsonFile(objectMapper, configClientProperties.getPath());
    }

    @Bean
    public ConfigUpdaterLoggerLevel configApplierLoggerLevel(LoggerConfigSourceJsonFile configSource) {
        return new ConfigUpdaterLoggerLevel(configSource);
    }
}
