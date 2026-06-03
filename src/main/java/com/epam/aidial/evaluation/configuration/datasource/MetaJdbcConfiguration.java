package com.epam.aidial.evaluation.configuration.datasource;

import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jooq.ExceptionTranslatorExecuteListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class MetaJdbcConfiguration {

    @Bean
    @Qualifier("metaRawJdbcTemplate")
    public JdbcTemplate metaRawJdbcTemplate(@Qualifier("metaDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @Qualifier("metaJdbcTemplate")
    public NamedParameterJdbcTemplate metaJdbcTemplate(@Qualifier("metaDataSource") DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    @Qualifier("metaTransactionManager")
    public PlatformTransactionManager metaTransactionManager(@Qualifier("metaDataSource") DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    @Qualifier("metaDsl")
    public DSLContext metaDsl(@Qualifier("metaDataSource") DataSource dataSource) {
        DefaultConfiguration config = new DefaultConfiguration();
        config.setDataSource(new TransactionAwareDataSourceProxy(dataSource));
        config.setSQLDialect(SQLDialect.POSTGRES);
        config.setSettings(new Settings().withRenderSchema(false));
        config.setExecuteListenerProvider(
                new DefaultExecuteListenerProvider(ExceptionTranslatorExecuteListener.DEFAULT));
        return DSL.using(config);
    }
}
