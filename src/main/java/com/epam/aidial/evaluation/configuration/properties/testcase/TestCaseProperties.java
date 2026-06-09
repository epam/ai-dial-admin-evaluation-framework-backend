package com.epam.aidial.evaluation.configuration.properties.testcase;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@LogExecution
@Validated
@ConfigurationProperties(prefix = "test-case")
public class TestCaseProperties {

    @Valid
    private Batch batch = new Batch();

    @Valid
    private Bulk bulk = new Bulk();

    @Getter
    @Setter
    public static class Batch {

        @Min(1)
        private int maxItems;
    }

    @Getter
    @Setter
    public static class Bulk {

        @Min(1)
        private int maxOperations;

        @Min(1)
        private int maxIdsPerSelector;

        @Min(1)
        private int maxItemOperations;
    }
}
