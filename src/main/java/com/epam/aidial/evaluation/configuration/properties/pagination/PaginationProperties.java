package com.epam.aidial.evaluation.configuration.properties.pagination;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Configuration
@LogExecution
@Validated
@ConfigurationProperties(prefix = "pagination")
public class PaginationProperties {

    @Min(1)
    private int defaultSize;

    @Min(1)
    private int maxSize;
}
