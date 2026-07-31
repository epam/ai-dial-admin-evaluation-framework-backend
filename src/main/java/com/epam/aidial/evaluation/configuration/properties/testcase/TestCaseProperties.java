package com.epam.aidial.evaluation.configuration.properties.testcase;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
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

    @Valid
    private MultiTurn multiTurn = new MultiTurn();

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

        @Min(1)
        private int maxDeleteIds;
    }

    @Getter
    @Setter
    public static class MultiTurn {

        /** Maximum number of turns a multi-turn test case may carry; over-cap cases are invalidated. */
        @Min(1)
        private int maxTurns;
    }
}
