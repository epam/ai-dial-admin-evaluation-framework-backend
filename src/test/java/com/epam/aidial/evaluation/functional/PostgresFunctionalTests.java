package com.epam.aidial.evaluation.functional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.metricprovider.MetricProviderClient;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricsDescriptionDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricsResponseDto;
import com.epam.aidial.evaluation.functional.config.PostgresFunctionalTestConfiguration;
import com.epam.aidial.evaluation.functional.tests.AnalyticsResultBatchWriteFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.AnalyticsResultCountFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.AnalyticsResultGetByIdFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.AnalyticsResultListFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.AnalyticsRetryFieldsFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.ApiKeyAuthenticationFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.CsvImportModeFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.DatasetCloneFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.DatasetCrudFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.DatasetDetachFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.DatasetFileFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.DatasetMigrationFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.DatasetScopedTestCaseFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.DatasetVisibilityFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.DeploymentFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.EvalResultsImportFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.EvalSummaryAggregationFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.EvalSummaryExportFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.EvalSummaryExportPageSizeFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.EvalSummaryFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.EvalSummaryStructuredQueryFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.EvaluationExecutorFailureModesFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.EvaluationMultipartFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.ExecutionSettingsFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.FileFieldFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.FileFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.GrafanaDisabledFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.GrafanaEnabledFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.HeaderBlacklistFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.JsonataRequestTemplateFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.LegacyDisabledTestCaseIdsFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.MaxLimitsFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.McpDeploymentFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.McpEvaluationRunFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.McpTestSuiteFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.McpTryItOutFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.MetricDeclarationFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.MetricScoreComputationFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.MetricScoreResultStructuredQueryFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.MultiRequestChainRunFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.MultiRequestSuiteValidationFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.MultiTurnCsvFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.MultiTurnFilterFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.MultiTurnRunFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.MultiTurnSharedDataFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.NoSecurityStartupSmokeTest;
import com.epam.aidial.evaluation.functional.tests.OidcSecurityStartupSmokeTest;
import com.epam.aidial.evaluation.functional.tests.PolymorphicBodyFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.PostgresTestCaseRunInputRepositoryFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.PostgresTestSuiteRunRepositoryFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.QuerySchemaDiscoveryFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.ResponseColumnFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.RevalidationCoercionFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.RevalidationTaskFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.RocAucScoreFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.RunComparisonFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.RunComparisonRepositoryFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.RunMetricSnapshotFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.StructuredQueryExecuteFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.SuiteSnapshotFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.SuiteValidationBindingFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.SuiteValidationFileRefFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TemplateBindingEdgeCaseFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TemplateVariableFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TestCaseBatchPatchFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TestCaseBatchPutFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TestCaseBulkPatchCapsFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TestCaseBulkPatchFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TestCaseConvenienceApiFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TestCaseFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TestCaseQueryAndFilterFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TestCaseRunInputsRetentionFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TestSuiteCloneFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TestSuiteDatasetFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TestSuiteFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TestSuiteMetricDefinitionFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TestSuiteRunFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TestSuiteRunSseFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TestSuiteStructuredQueryFunctionalTests;
import com.epam.aidial.evaluation.functional.tests.TryItOutFunctionalTests;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.MetricProviderSyncJob;
import com.epam.aidial.evaluation.service.domain.dto.MetricDeclarationResponseDto;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestPropertySource(
        properties = {
            "datasource.meta.vendor=POSTGRES",
            "datasource.meta.auth.type=basic",
            "datasource.analytics.vendor=POSTGRES",
            "datasource.analytics.auth.type=basic",
            "spring.flyway.connect-retries=10",
            "config.rest.security.mode=none",
            "spring.http.client.factory=jdk",
            "dial.api-key=test-api-key",
            "revalidation.batch-size=2"
        })
@Import(PostgresFunctionalTestConfiguration.class)
@NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.INHERIT)
public class PostgresFunctionalTests extends DialClientMockingFunctionalTests {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.4")
            .withInitScript("test/init-test-databases.sql")
            .withCommand("postgres", "-c", "max_connections=400");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Meta datasource � points to the default database created by Testcontainers
        registry.add("postgres.meta.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("postgres.meta.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("postgres.meta.datasource.username", POSTGRES::getUsername);
        registry.add("postgres.meta.datasource.password", POSTGRES::getPassword);
        registry.add("postgres.meta.datasource.schema", () -> "public");

        // Analytics datasource � points to the analytics database created by init script
        registry.add(
                "postgres.analytics.datasource.url",
                () -> POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/evaluation_analytics_db"));
        registry.add("postgres.analytics.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("postgres.analytics.datasource.username", POSTGRES::getUsername);
        registry.add("postgres.analytics.datasource.password", POSTGRES::getPassword);
        registry.add("postgres.analytics.datasource.schema", () -> "public");
    }

    public static PostgreSQLContainer getContainer() {
        return POSTGRES;
    }

    @Nested
    class MetricScoreComputationTests extends MetricScoreComputationFunctionalTests {}

    @Nested
    class RunComparisonRepositoryTests extends RunComparisonRepositoryFunctionalTests {}

    @Nested
    class RunComparisonTests extends RunComparisonFunctionalTests {}

    @Nested
    class DatasetCrudTests extends DatasetCrudFunctionalTests {}

    @Nested
    class DatasetVisibilityTests extends DatasetVisibilityFunctionalTests {}

    @Nested
    class DatasetDetachTests extends DatasetDetachFunctionalTests {}

    @Nested
    class DatasetCloneTests extends DatasetCloneFunctionalTests {}

    @Nested
    class DatasetScopedTestCaseTests extends DatasetScopedTestCaseFunctionalTests {}

    @Nested
    class TestSuiteDatasetTests extends TestSuiteDatasetFunctionalTests {}

    @Nested
    class RevalidationTaskTests extends RevalidationTaskFunctionalTests {}

    @Nested
    class DatasetMigrationTests extends DatasetMigrationFunctionalTests {}

    @Nested
    class DeploymentTests extends DeploymentFunctionalTests {}

    @Nested
    class TestSuiteTests extends TestSuiteFunctionalTests {}

    @Nested
    class MultiRequestSuiteValidationTests extends MultiRequestSuiteValidationFunctionalTests {}

    @Nested
    class TestCaseTests extends TestCaseFunctionalTests {}

    @Nested
    class TestCaseBatchPutTests extends TestCaseBatchPutFunctionalTests {}

    @Nested
    class TestCaseBatchPatchTests extends TestCaseBatchPatchFunctionalTests {}

    @Nested
    class TestCaseBulkPatchTests extends TestCaseBulkPatchFunctionalTests {}

    @Nested
    @TestPropertySource(properties = {"test-case.bulk.max-operations=3", "test-case.bulk.max-ids-per-selector=3"})
    class TestCaseBulkPatchCapsTests extends TestCaseBulkPatchCapsFunctionalTests {}

    @Nested
    class MetricDeclarationTests extends MetricDeclarationFunctionalTests {}

    @Nested
    class TestSuiteMetricDefinitionTests extends TestSuiteMetricDefinitionFunctionalTests {}

    @Nested
    class TemplateVariableTests extends TemplateVariableFunctionalTests {}

    @Nested
    class TestCaseConvenienceApiTests extends TestCaseConvenienceApiFunctionalTests {}

    @Nested
    class MaxLimitsTests extends MaxLimitsFunctionalTests {}

    @Nested
    class TemplateBindingEdgeCaseTests extends TemplateBindingEdgeCaseFunctionalTests {}

    @Nested
    class TestSuiteRunTests extends TestSuiteRunFunctionalTests {}

    @Nested
    class LegacyDisabledTestCaseIdsTests extends LegacyDisabledTestCaseIdsFunctionalTests {}

    @Nested
    class EvalResultsImportTests extends EvalResultsImportFunctionalTests {}

    @Nested
    class TestSuiteRunSseTests extends TestSuiteRunSseFunctionalTests {}

    @Nested
    class AnalyticsResultBatchWriteTests extends AnalyticsResultBatchWriteFunctionalTests {}

    @Nested
    class AnalyticsResultListTests extends AnalyticsResultListFunctionalTests {}

    @Nested
    class AnalyticsResultGetByIdTests extends AnalyticsResultGetByIdFunctionalTests {}

    @Nested
    class AnalyticsResultCountTests extends AnalyticsResultCountFunctionalTests {}

    @Nested
    class CsvImportModeTests extends CsvImportModeFunctionalTests {}

    @Nested
    class TryItOutTests extends TryItOutFunctionalTests {}

    @Nested
    class ResponseColumnTests extends ResponseColumnFunctionalTests {}

    @Nested
    class ExecutionSettingsTests extends ExecutionSettingsFunctionalTests {}

    @Nested
    @TestPropertySource(properties = {"test-suite-run.execution.cancellation-grace-period-ms=1000"})
    class EvaluationExecutorFailureModesTests extends EvaluationExecutorFailureModesFunctionalTests {}

    @Nested
    class MultiTurnRunTests extends MultiTurnRunFunctionalTests {}

    @Nested
    class MultiRequestChainRunTests extends MultiRequestChainRunFunctionalTests {}

    @Nested
    class MultiTurnFilterTests extends MultiTurnFilterFunctionalTests {}

    @Nested
    class MultiTurnCsvTests extends MultiTurnCsvFunctionalTests {}

    @Nested
    class MultiTurnSharedDataTests extends MultiTurnSharedDataFunctionalTests {}

    @Nested
    class JsonataRequestTemplateTests extends JsonataRequestTemplateFunctionalTests {}

    @Nested
    class AnalyticsRetryFieldsTests extends AnalyticsRetryFieldsFunctionalTests {}

    @Nested
    class HeaderBlacklistTests extends HeaderBlacklistFunctionalTests {}

    @Nested
    @TestPropertySource(
            properties = {
                "app.grafana.base-url=http://grafana:3000",
                "otel.sdk.disabled=false",
                "otel.traces.exporter=none",
                "otel.metrics.exporter=none",
                "otel.logs.exporter=none"
            })
    class GrafanaEnabledTests extends GrafanaEnabledFunctionalTests {}

    @Nested
    class GrafanaDisabledTests extends GrafanaDisabledFunctionalTests {}

    @Nested
    class NoSecurityStartupTests extends NoSecurityStartupSmokeTest {}

    @Nested
    @TestPropertySource(
            properties = {
                "config.rest.security.mode=oidc",
                "config.rest.security.disable-swagger-authorization=true",
                "providers.test.issuer=https://issuer.example.com",
                "providers.test.jwkSetUri=https://issuer.example.com/.well-known/jwks.json",
                "providers.test.audiences[0]=test-audience",
                "providers.test.roleClaims[0]=roles",
                "providers.test.allowedRoles[0]=admin",
                "providers.test.principalClaim=sub"
            })
    class OidcStartupTests extends OidcSecurityStartupSmokeTest {}

    @Nested
    @TestPropertySource(
            properties = {
                "config.rest.security.mode=oidc",
                "config.rest.security.disable-swagger-authorization=true",
                "providers.test.issuer=https://issuer.example.com",
                "providers.test.jwkSetUri=https://issuer.example.com/.well-known/jwks.json",
                "providers.test.audiences[0]=test-audience",
                "providers.test.roleClaims[0]=roles",
                "providers.test.allowedRoles[0]=admin",
                "providers.test.principalClaim=sub",
                "config.rest.security.api-key.enabled=true",
                "config.rest.security.api-key.core-url=http://localhost:1",
                "config.rest.security.api-key.roles-mapping={\"admin\":[\"admin\"]}",
                "config.rest.security.api-key.startup-probe=false"
            })
    class ApiKeyAuthenticationTests extends ApiKeyAuthenticationFunctionalTests {}

    @Nested
    class FileTests extends FileFunctionalTests {}

    @Nested
    class DatasetFileTests extends DatasetFileFunctionalTests {}

    @Nested
    class FileFieldTests extends FileFieldFunctionalTests {}

    @Nested
    class SuiteValidationFileRefTests extends SuiteValidationFileRefFunctionalTests {}

    @Nested
    class SuiteValidationBindingTests extends SuiteValidationBindingFunctionalTests {}

    @Nested
    class PolymorphicBodyTests extends PolymorphicBodyFunctionalTests {}

    @Nested
    class EvaluationMultipartTests extends EvaluationMultipartFunctionalTests {}

    @Nested
    class EvalSummaryTests extends EvalSummaryFunctionalTests {}

    @Nested
    class EvalSummaryAggregationTests extends EvalSummaryAggregationFunctionalTests {}

    @Nested
    class EvalSummaryExportTests extends EvalSummaryExportFunctionalTests {}

    @Nested
    @TestPropertySource(properties = {"csv.export.page-size=2"})
    class EvalSummaryExportPageSizeTests extends EvalSummaryExportPageSizeFunctionalTests {}

    @Nested
    class RunMetricSnapshotTests extends RunMetricSnapshotFunctionalTests {}

    @Nested
    class McpDeploymentTests extends McpDeploymentFunctionalTests {}

    @Nested
    class McpTestSuiteTests extends McpTestSuiteFunctionalTests {}

    @Nested
    class McpTryItOutTests extends McpTryItOutFunctionalTests {}

    @Nested
    class McpEvaluationRunTests extends McpEvaluationRunFunctionalTests {}

    @Nested
    class TestSuiteCloneTests extends TestSuiteCloneFunctionalTests {}

    @Nested
    class SuiteSnapshotTests extends SuiteSnapshotFunctionalTests {}

    @Nested
    class PostgresTestSuiteRunRepositoryTests extends PostgresTestSuiteRunRepositoryFunctionalTests {}

    @Nested
    class PostgresTestCaseRunInputRepositoryTests extends PostgresTestCaseRunInputRepositoryFunctionalTests {}

    @Nested
    class TestCaseRunInputsRetentionTests extends TestCaseRunInputsRetentionFunctionalTests {}

    @Nested
    class RevalidationCoercionTests extends RevalidationCoercionFunctionalTests {}

    @Nested
    class QuerySchemaDiscoveryTests extends QuerySchemaDiscoveryFunctionalTests {}

    @Nested
    class TestSuiteStructuredQueryTests extends TestSuiteStructuredQueryFunctionalTests {}

    @Nested
    class EvalSummaryStructuredQueryTests extends EvalSummaryStructuredQueryFunctionalTests {}

    @Nested
    class MetricScoreResultStructuredQueryTests extends MetricScoreResultStructuredQueryFunctionalTests {}

    @Nested
    class RocAucScoreTests extends RocAucScoreFunctionalTests {}

    @Nested
    class StructuredQueryExecuteTests extends StructuredQueryExecuteFunctionalTests {}

    @Nested
    class TestCaseQueryAndFilterTests extends TestCaseQueryAndFilterFunctionalTests {}

    @Nested
    @DisplayName("Metric provider sync job")
    @TestPropertySource(
            properties = {
                "metric-providers.sync.enabled=true",
                "metric-providers.providers.sync-test-provider.enabled=true",
                "metric-providers.providers.sync-test-provider.base-url=http://localhost:9999",
                "metric-providers.providers.disabled-test-provider.enabled=false",
                "metric-providers.providers.disabled-test-provider.base-url=http://localhost:9998"
            })
    class MetricProviderSyncJobTests {

        @Autowired
        private TestRestTemplate restTemplate;

        @Autowired
        private org.springframework.core.env.Environment environment;

        @Autowired
        private MetricProviderSyncJob metricProviderSyncJob;

        @Autowired
        private MetricProviderClient metricProviderClientFromContext;

        @Test
        @DisplayName("sync run populates catalog without blocking startup")
        void syncRunPopulatesCatalog() {
            when(metricProviderClientFromContext.getMetrics(eq("sync-test-provider")))
                    .thenReturn(MetricsResponseDto.builder()
                            .metrics(List.of(MetricsDescriptionDto.builder()
                                    .name("SyncedMetric")
                                    .description("From sync test")
                                    .configSchema("{}")
                                    .inputSchema("{}")
                                    .outputSchema("{}")
                                    .build()))
                            .build());

            metricProviderSyncJob.runScheduledSync();

            // Spring Boot 4: Retrieve local.server.port dynamically from Environment instead of @LocalServerPort
            String portStr = environment.getProperty("local.server.port");
            int port = portStr != null ? Integer.parseInt(portStr) : 8080;
            String url =
                    "http://localhost:" + port + "/api/v1/metric-declarations?page=0&size=20&includeTotalCount=true";
            ResponseEntity<PageResponseDto<MetricDeclarationResponseDto>> response =
                    restTemplate.exchange(url, HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getContent()).hasSize(1);
            assertThat(response.getBody().getContent().getFirst().getName()).isEqualTo("SyncedMetric");
            assertThat(response.getBody().getContent().getFirst().getProviderId())
                    .isEqualTo("sync-test-provider");
        }

        @Test
        @DisplayName("skips a disabled provider entry while syncing the enabled ones")
        void disabledProviderIsNotCalled() {
            when(metricProviderClientFromContext.getMetrics(eq("sync-test-provider")))
                    .thenReturn(MetricsResponseDto.builder().metrics(List.of()).build());

            metricProviderSyncJob.runScheduledSync();

            verify(metricProviderClientFromContext).getMetrics("sync-test-provider");
            verify(metricProviderClientFromContext, never()).getMetrics("disabled-test-provider");
        }
    }
}
