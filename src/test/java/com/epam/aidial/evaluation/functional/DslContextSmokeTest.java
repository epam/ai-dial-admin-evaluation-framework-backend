package com.epam.aidial.evaluation.functional;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.functional.config.PostgresFunctionalTestConfiguration;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
        properties = {
            "datasource.meta.vendor=POSTGRES",
            "datasource.meta.auth.type=basic",
            "datasource.analytics.vendor=POSTGRES",
            "datasource.analytics.auth.type=basic",
            "spring.flyway.connect-retries=10",
            "config.rest.security.mode=none",
            "spring.http.client.factory=jdk",
            "dial.api-key=test-api-key"
        })
@Import(PostgresFunctionalTestConfiguration.class)
public class DslContextSmokeTest {

    private static final PostgreSQLContainer<?> POSTGRES = PostgresFunctionalTests.getContainer();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("postgres.meta.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("postgres.meta.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("postgres.meta.datasource.username", POSTGRES::getUsername);
        registry.add("postgres.meta.datasource.password", POSTGRES::getPassword);
        registry.add("postgres.meta.datasource.schema", () -> "public");

        registry.add(
                "postgres.analytics.datasource.url",
                () -> POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/evaluation_analytics_db"));
        registry.add("postgres.analytics.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("postgres.analytics.datasource.username", POSTGRES::getUsername);
        registry.add("postgres.analytics.datasource.password", POSTGRES::getPassword);
        registry.add("postgres.analytics.datasource.schema", () -> "public");
    }

    @Autowired
    @Qualifier("metaDsl")
    private DSLContext metaDsl;

    @Autowired
    @Qualifier("analyticsDsl")
    private DSLContext analyticsDsl;

    @Test
    void metaDslContextIsPresent() {
        assertThat(metaDsl).isNotNull();
        assertThat(metaDsl.dialect()).isEqualTo(SQLDialect.POSTGRES);
    }

    @Test
    void analyticsDslContextIsPresent() {
        assertThat(analyticsDsl).isNotNull();
        assertThat(analyticsDsl.dialect()).isEqualTo(SQLDialect.POSTGRES);
    }
}
