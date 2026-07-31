package com.epam.aidial.evaluation.configuration.datasource;

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
            @Value("${datasource.meta.vendor}") String metaVendor,
            @Value("${postgres.meta.datasource.schema:public}") String metaSchema,
            DatasourceValidationResult validationResult) {
        String location = "classpath:db/migration/meta/" + metaVendor;
        log.info("Configuring meta Flyway migration at location: {}, schema: {}", location, metaSchema);

        Flyway flyway = Flyway.configure()
                .dataSource(metaDataSource)
                .locations(location)
                .defaultSchema(metaSchema)
                .baselineOnMigrate(true)
                .validateMigrationNaming(true)
                .load();
        flyway.migrate();
        return flyway;
    }
}
