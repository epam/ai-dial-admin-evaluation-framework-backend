package com.epam.aidial.evaluation.service.infrastructure.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component("analyticsDatabase")
@RequiredArgsConstructor
public class AnalyticsDatabaseHealthIndicator implements HealthIndicator {

    @Qualifier("analyticsRawJdbcTemplate")
    private final JdbcTemplate jdbcTemplate;

    @Override
    public Health health() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return Health.up()
                    .withDetail("database", "PostgreSQL (analytics)")
                    .withDetail("status", "Connection successful")
                    .build();
        } catch (Exception ex) {
            log.error("Analytics database health check failed", ex);
            return Health.down()
                    .withDetail("database", "PostgreSQL (analytics)")
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
