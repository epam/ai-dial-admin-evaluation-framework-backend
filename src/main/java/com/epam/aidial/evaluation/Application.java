package com.epam.aidial.evaluation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jooq.autoconfigure.JooqAutoConfiguration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

// JooqAutoConfiguration is excluded: this is a dual-datasource project that builds its own
// meta/analytics DSLContext beans. Spring Boot 4's JooqAutoConfiguration tries to wire a single
// PlatformTransactionManager into its transactionProvider bean, which is ambiguous here
// (metaTransactionManager + analyticsTransactionManager).
// FlywayAutoConfiguration is excluded: custom MetaFlywayConfiguration and AnalyticsFlywayConfiguration
// manage migrations for both datasources; auto-configuration would fail on the ambiguous datasource.
@SpringBootApplication(exclude = {JooqAutoConfiguration.class, FlywayAutoConfiguration.class})
@EnableScheduling
@EnableAsync
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true, jsr250Enabled = true)
@EnableAspectJAutoProxy
@ConfigurationPropertiesScan(basePackages = "com.epam.aidial.evaluation.configuration.properties")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
