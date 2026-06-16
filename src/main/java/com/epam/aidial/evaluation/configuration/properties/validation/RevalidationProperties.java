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
@ConfigurationProperties(prefix = "validation.revalidation")
public class RevalidationProperties {

    @Min(1)
    private int batchSize;

    @Min(1)
    private int timeoutMinutes;
}
