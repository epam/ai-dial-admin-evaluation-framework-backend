package com.epam.aidial.evaluation.service.infrastructure.health;

import com.epam.aidial.evaluation.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
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
