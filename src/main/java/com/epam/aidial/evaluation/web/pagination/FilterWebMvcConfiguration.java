package com.epam.aidial.evaluation.web.pagination;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link FilterParamArgumentResolver} so controllers can bind repeatable
 * {@code filter} query parameters via the {@link FilterParam} annotation.
 */
@Configuration
@LogExecution
@RequiredArgsConstructor
public class FilterWebMvcConfiguration implements WebMvcConfigurer {

    private final FilterParamArgumentResolver filterParamArgumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(filterParamArgumentResolver);
    }
}
