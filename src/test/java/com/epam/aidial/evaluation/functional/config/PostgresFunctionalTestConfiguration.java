package com.epam.aidial.evaluation.functional.config;

import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteMetricDefinitionRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.functional.config.persistence.PostgresTestPersistenceService;
import com.epam.aidial.evaluation.functional.config.persistence.TestPersistenceService;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetricDeclarationTestDataProvider;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@TestConfiguration
@Import(FunctionalTestConfiguration.class)
public class PostgresFunctionalTestConfiguration {

    @Bean
    public TestPersistenceService testPersistenceService(
            @Qualifier("metaRawJdbcTemplate") JdbcTemplate metaJdbcTemplate,
            @Qualifier("analyticsJdbcTemplate") NamedParameterJdbcTemplate analyticsJdbcTemplate) {
        return new PostgresTestPersistenceService(metaJdbcTemplate, analyticsJdbcTemplate);
    }

    @Bean
    public MetricDeclarationTestDataProvider metricDeclarationTestDataProvider(
            @Qualifier("metaJdbcTemplate") NamedParameterJdbcTemplate metaJdbcTemplate) {
        return new MetricDeclarationTestDataProvider(metaJdbcTemplate);
    }

    @Bean
    public MetaTestDataHelper metaTestDataHelper(
            TestSuiteRepository testSuiteRepository,
            TestSuiteRunRepository testSuiteRunRepository,
            TestSuiteMetricDefinitionRepository tsmdRepository,
            TestCaseRepository testCaseRepository,
            DatasetRepository datasetRepository,
            @Qualifier("metaDsl") DSLContext metaDsl) {
        return new MetaTestDataHelper(
                testSuiteRepository,
                testSuiteRunRepository,
                tsmdRepository,
                testCaseRepository,
                datasetRepository,
                metaDsl);
    }

    @Bean
    public AnalyticsTestDataHelper analyticsTestDataHelper(@Qualifier("analyticsDsl") DSLContext analyticsDsl) {
        return new AnalyticsTestDataHelper(analyticsDsl);
    }
}
