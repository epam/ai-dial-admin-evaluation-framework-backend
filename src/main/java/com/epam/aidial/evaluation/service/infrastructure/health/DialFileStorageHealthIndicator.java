package com.epam.aidial.evaluation.service.infrastructure.health;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Slf4j
@Component("dialFileStorage")
@LogExecution
@RequiredArgsConstructor
public class DialFileStorageHealthIndicator implements HealthIndicator {

    private final DialFileClient dialFileClient;

    @Override
    public Health health() {
        try {
            dialFileClient.getBucket();
            return Health.up().withDetail("storage", "DIAL Core File Storage").build();
        } catch (Exception ex) {
            log.warn("DIAL file storage health check failed: {}", ex.getMessage(), ex);
            return Health.down()
                    .withDetail("storage", "DIAL Core File Storage")
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
