package com.epam.aidial.evaluation.configuration.properties.validation;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "validation")
public class ValidationProperties {

    @Min(0)
    private int maxWarningsPerCase;

    @Min(1)
    private int maxTemplateSizeBytes = 65536; // 64KB default

    @Min(1)
    private int maxBindingsCount = 64;
}
