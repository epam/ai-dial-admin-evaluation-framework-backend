package com.epam.aidial.evaluation.functional;

import com.epam.aidial.evaluation.functional.config.ClickHouseFunctionalTestConfiguration;
import com.epam.aidial.evaluation.functional.tests.AnalyticsResultBatchWriteFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.AnalyticsResultCountFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.AnalyticsResultGetByIdFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.AnalyticsResultListFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.EvalResultsImportFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.EvalSummaryAggregationFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.EvalSummaryFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.EvalSummaryStructuredQueryFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.MetricScoreComputationFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.RunComparisonFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.RunComparisonRepositoryFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.RunMetricSnapshotFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.StructuredQueryExecuteFunctionalTests;
import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Functional-test entry point for {@code datasource.analytics.vendor=CLICKHOUSE}. Meta stays on Postgres
 * (as in production — only the analytics vendor is switchable), so this class runs two singleton
 * containers: the same Postgres image {@link PostgresFunctionalTests} uses for meta, plus a ClickHouse
 * server for analytics.
 *
 * <p>Only the analytics-touching abstract suites are attached as {@code @Nested} classes here; the purely
 * meta-scoped ones are already covered by {@link PostgresFunctionalTests} and would only duplicate runtime.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(
        properties = {
            "datasource.meta.vendor=POSTGRES",
            "datasource.meta.auth.type=basic",
            "datasource.analytics.vendor=CLICKHOUSE",
            "datasource.analytics.auth.type=basic",
            "spring.flyway.connect-retries=10",
            "config.rest.security.mode=none",
            "spring.http.client.factory=jdk",
            "dial.api-key=test-api-key",
            "revalidation.batch-size=2"
        })
@Import(ClickHouseFunctionalTestConfiguration.class)
@NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.INHERIT)
public class ClickHouseFunctionalTests extends DialClientMockingFunctionalTests {

    private static final String CLICKHOUSE_IMAGE = "clickhouse/clickhouse-server:25.8";
    private static final String CLICKHOUSE_DATABASE = "evaluation_analytics";
    private static final String CLICKHOUSE_USER = "clickhouse";
    private static final String CLICKHOUSE_PASSWORD = "clickhouse";
    private static final int CLICKHOUSE_HTTP_PORT = 8123;

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.4")
            .withInitScript("test/init-test-databases.sql")
            .withCommand("postgres", "-c", "max_connections=400");

    private static final ClickHouseContainer CLICKHOUSE = new ClickHouseContainer()
            .withEnv("CLICKHOUSE_DB", CLICKHOUSE_DATABASE)
            .withEnv("CLICKHOUSE_USER", CLICKHOUSE_USER)
            .withEnv("CLICKHOUSE_PASSWORD", CLICKHOUSE_PASSWORD)
            .withExposedPorts(CLICKHOUSE_HTTP_PORT)
            .waitingFor(Wait.forHttp("/ping").forPort(CLICKHOUSE_HTTP_PORT).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(3));

    static {
        POSTGRES.start();
        CLICKHOUSE.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Meta datasource - points to the default database created by Testcontainers
        registry.add("postgres.meta.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("postgres.meta.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("postgres.meta.datasource.username", POSTGRES::getUsername);
        registry.add("postgres.meta.datasource.password", POSTGRES::getPassword);
        registry.add("postgres.meta.datasource.schema", () -> "public");

        // Analytics datasource - the ClickHouse container's CLICKHOUSE_DB database
        registry.add(
                "clickhouse.analytics.datasource.url",
                () -> String.format(
                        "jdbc:ch://%s:%d/%s",
                        CLICKHOUSE.getHost(), CLICKHOUSE.getMappedPort(CLICKHOUSE_HTTP_PORT), CLICKHOUSE_DATABASE));
        registry.add("clickhouse.analytics.datasource.connection-params", () -> "");
        registry.add("clickhouse.analytics.datasource.driver-class-name", () -> "com.clickhouse.jdbc.ClickHouseDriver");
        registry.add("clickhouse.analytics.datasource.username", () -> CLICKHOUSE_USER);
        registry.add("clickhouse.analytics.datasource.password", () -> CLICKHOUSE_PASSWORD);
        registry.add("clickhouse.analytics.datasource.database", () -> CLICKHOUSE_DATABASE);
    }

    public static PostgreSQLContainer getMetaContainer() {
        return POSTGRES;
    }

    @Nested
    class AnalyticsResultBatchWriteTests extends AnalyticsResultBatchWriteFunctionalTests {}

    @Nested
    class AnalyticsResultListTests extends AnalyticsResultListFunctionalTests {}

    @Nested
    class AnalyticsResultGetByIdTests extends AnalyticsResultGetByIdFunctionalTests {}

    @Nested
    class AnalyticsResultCountTests extends AnalyticsResultCountFunctionalTests {}

    @Nested
    class EvalSummaryTests extends EvalSummaryFunctionalTests {}

    @Nested
    class EvalSummaryAggregationTests extends EvalSummaryAggregationFunctionalTests {}

    @Nested
    class EvalSummaryStructuredQueryTests extends EvalSummaryStructuredQueryFunctionalTests {}

    @Nested
    class RunMetricSnapshotTests extends RunMetricSnapshotFunctionalTests {}

    @Nested
    class MetricScoreComputationTests extends MetricScoreComputationFunctionalTests {}

    @Nested
    class StructuredQueryExecuteTests extends StructuredQueryExecuteFunctionalTests {}

    @Nested
    class RunComparisonTests extends RunComparisonFunctionalTests {}

    @Nested
    class RunComparisonRepositoryTests extends RunComparisonRepositoryFunctionalTests {}

    @Nested
    class EvalResultsImportTests extends EvalResultsImportFunctionalTests {}

    /** Self-typed GenericContainer subclass so the builder calls stay type-safe without raw types. */
    private static final class ClickHouseContainer extends GenericContainer<ClickHouseContainer> {
        private ClickHouseContainer() {
            super(CLICKHOUSE_IMAGE);
        }
    }
}
