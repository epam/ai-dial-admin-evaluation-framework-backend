package com.epam.aidial.evaluation.configuration.properties.csv;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Configuration
@LogExecution
@Validated
@ConfigurationProperties(prefix = "csv.import")
public class CsvImportProperties {

    @NotNull
    private DataSize maxFileSize;

    @Min(1)
    private int maxRows;

    @Min(1)
    private int batchSize;
}
