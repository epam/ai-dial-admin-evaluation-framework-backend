package com.epam.aidial.evaluation.configuration.logging;

import com.epam.aidial.evaluation.configuration.security.AuthorizationHeaderInterceptor;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@LogExecution
public class WebMvcConfig implements WebMvcConfigurer {

    @Bean
    public CorrelationIdInterceptor correlationIdInterceptor() {
        return new CorrelationIdInterceptor();
    }

    @Bean
    public AuthorizationHeaderInterceptor authorizationHeaderInterceptor() {
        return new AuthorizationHeaderInterceptor();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(correlationIdInterceptor()).order(Ordered.HIGHEST_PRECEDENCE);
        registry.addInterceptor(authorizationHeaderInterceptor()).order(Ordered.HIGHEST_PRECEDENCE + 1);
    }
}
