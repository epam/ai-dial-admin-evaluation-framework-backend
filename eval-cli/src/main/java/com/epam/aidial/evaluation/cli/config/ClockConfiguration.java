package com.epam.aidial.evaluation.cli.config;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides the {@link Clock} bean for the eval-cli module. Distinct from the EF backend's own bean. */
@Configuration
@LogExecution
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
