package com.epam.aidial.evaluation.client.dialadas;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "dial.adas")
public class DialAdasProperties {

    @NotBlank
    private String baseUrl;

    @Min(0)
    private int connectTimeoutMs;

    @Min(0)
    private int readTimeoutMs;
}
