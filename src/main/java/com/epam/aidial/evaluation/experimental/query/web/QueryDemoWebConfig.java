package com.epam.aidial.evaluation.experimental.query.web;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the static schema-discovery / query-builder demos under the clean {@code /demo} path by
 * forwarding to the static resources {@code static/demo/schema.html} and
 * {@code static/demo/query.html}. The pages are plain HTML/JS that call the {@code /api/v0/queries}
 * endpoints from the same origin. Local-testing aid only — not intended for production use.
 */
@Configuration
@LogExecution
public class QueryDemoWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/demo").setViewName("forward:/demo/schema.html");
        registry.addViewController("/demo/").setViewName("forward:/demo/schema.html");
        registry.addViewController("/demo/schema").setViewName("forward:/demo/schema.html");
        registry.addViewController("/demo/query").setViewName("forward:/demo/query.html");
    }
}
