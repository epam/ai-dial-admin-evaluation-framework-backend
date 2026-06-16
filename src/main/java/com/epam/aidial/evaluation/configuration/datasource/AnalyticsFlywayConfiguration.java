package com.epam.aidial.evaluation.configuration.datasource;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
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
public class AnalyticsFlywayConfiguration {

    @Bean
    public Flyway analyticsFlywayMigration(
            @Qualifier("analyticsDataSource") DataSource analyticsDataSource,
            @Value("${datasource.analytics.vendor}") String analyticsVendor,
            @Value("${postgres.analytics.datasource.schema:public}") String analyticsSchema,
            DatasourceValidationResult validationResult) {
        String location = "classpath:db/migration/analytics/" + analyticsVendor;
        log.info("Configuring analytics Flyway migration at location: {}, schema: {}", location, analyticsSchema);

        Flyway flyway = Flyway.configure()
                .dataSource(analyticsDataSource)
                .locations(location)
                .defaultSchema(analyticsSchema)
                .baselineOnMigrate(true)
                .validateMigrationNaming(true)
                .load();
        flyway.migrate();
        return flyway;
    }
}
