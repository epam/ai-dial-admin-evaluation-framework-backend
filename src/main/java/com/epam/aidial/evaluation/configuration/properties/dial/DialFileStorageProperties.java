package com.epam.aidial.evaluation.configuration.properties.dial;

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
@ConfigurationProperties(prefix = "dial.file-storage")
public class DialFileStorageProperties {

    private String bucketAlias;

    @Min(1)
    private long maxFileSizeBytes;

    @Min(1)
    private int maxFilesPerSuite;

    @Min(1)
    private int maxFilesPerDataset;

    @Min(0)
    private int connectTimeoutMs;

    @Min(0)
    private int readTimeoutMs;
}
