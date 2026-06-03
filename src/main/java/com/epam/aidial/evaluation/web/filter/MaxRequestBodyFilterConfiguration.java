package com.epam.aidial.evaluation.web.filter;

import com.epam.aidial.evaluation.configuration.properties.analytics.AnalyticsResultsProperties;
import com.epam.aidial.evaluation.configuration.properties.analytics.EvalSummaryProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class MaxRequestBodyFilterConfiguration {

    @Bean
    public FilterRegistrationBean<MaxRequestBodyFilter> maxRequestBodyFilterRegistration(
            AnalyticsResultsProperties analyticsProperties, ObjectMapper objectMapper) {
        MaxRequestBodyFilter filter =
                new MaxRequestBodyFilter(analyticsProperties.getBatch().getMaxRequestSizeBytes(), objectMapper);

        FilterRegistrationBean<MaxRequestBodyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/v1/analytics/test-case-results");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<MaxRequestBodyFilter> evalSummaryMaxRequestBodyFilterRegistration(
            EvalSummaryProperties evalSummaryProperties, ObjectMapper objectMapper) {
        MaxRequestBodyFilter filter =
                new MaxRequestBodyFilter(evalSummaryProperties.getBatch().getMaxRequestSizeBytes(), objectMapper);

        FilterRegistrationBean<MaxRequestBodyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/v1/analytics/eval-summaries");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 1);
        return registration;
    }
}
