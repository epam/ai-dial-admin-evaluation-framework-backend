package com.epam.aidial.evaluation.configuration.properties.dial;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "dial")
public class DialProperties {

    @NotBlank
    private String apiKey;
}
