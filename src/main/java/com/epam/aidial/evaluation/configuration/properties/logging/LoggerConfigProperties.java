package com.epam.aidial.evaluation.configuration.properties.logging;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "logger.configuration")
public class LoggerConfigProperties {
    private String path;
}
