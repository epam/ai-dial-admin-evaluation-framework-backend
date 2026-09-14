package com.epam.aidial.evaluation.configuration.datasource;

import com.epam.aidial.evaluation.configuration.datasource.migration.V1_33__CopyRunMetricSnapshotsFromAnalytics;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Slf4j
@LogExecution
public class MetaFlywayConfiguration {

    @Bean
    public Flyway metaFlywayMigration(
            @Qualifier("metaDataSource") DataSource metaDataSource,
            @Qualifier("analyticsDataSource") DataSource analyticsDataSource,
            @Value("${datasource.meta.vendor}") String metaVendor,
            @Value("${postgres.meta.datasource.schema:public}") String metaSchema,
            @Value("${postgres.analytics.datasource.schema:public}") String analyticsSchema,
            DatasourceValidationResult validationResult,
            // Forces this bean to be created after the analytics Flyway bean, so the
            // V1_33 copy migration below always reads a fully migrated analytics source
            // (design D3). Not otherwise referenced — its only role is ordering. There are two
            // Flyway beans, so the qualifier (matching AnalyticsFlywayConfiguration's bean name)
            // is required to disambiguate; parameter-name fallback alone is not guaranteed.
            @Qualifier("analyticsFlywayMigration") Flyway analyticsFlywayMigration) {
        String location = "classpath:db/migration/meta/" + metaVendor;
        log.info("Configuring meta Flyway migration at location: {}, schema: {}", location, metaSchema);

        Flyway flyway = Flyway.configure()
                .dataSource(metaDataSource)
                .locations(location)
                .defaultSchema(metaSchema)
                .baselineOnMigrate(true)
                .validateMigrationNaming(true)
                .javaMigrations(new V1_33__CopyRunMetricSnapshotsFromAnalytics(analyticsDataSource, analyticsSchema))
                .load();
        flyway.migrate();
        return flyway;
    }
}
