package com.epam.aidial.evaluation.configuration.properties.grafana;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Configuration
@LogExecution
@Validated
@ConfigurationProperties(prefix = "app.grafana")
public class GrafanaProperties {

    private String baseUrl;

    private String tempoDatasourceUid;

    private int orgId;
}
