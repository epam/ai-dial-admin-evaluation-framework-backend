package com.epam.aidial.evaluation.experimental.query.web;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the static schema-discovery / query-builder / run-dashboard demos under the clean
 * {@code /demo} path by forwarding to the static resources {@code static/demo/schema.html},
 * {@code static/demo/query.html} and {@code static/demo/dashboard.html}. The pages are plain HTML/JS
 * that call the {@code /api/v1/queries} (and, for the dashboard, {@code /api/v1/test-suite-runs})
 * endpoints from the same origin. Local-testing aid only — not intended for production use.
 *
 * <p>TODO: remove before going to prod — this whole class, and the {@code static/demo/*.html} pages
 * it forwards to, are temporary demo scaffolding and must not ship to production.
 */
// TODO: remove before going to prod — temporary demo UI scaffolding.
@Configuration
@LogExecution
public class QueryDemoWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // TODO: remove before going to prod — temporary demo UI routes.
        registry.addViewController("/demo").setViewName("forward:/demo/schema.html");
        registry.addViewController("/demo/").setViewName("forward:/demo/schema.html");
        registry.addViewController("/demo/schema").setViewName("forward:/demo/schema.html");
        registry.addViewController("/demo/query").setViewName("forward:/demo/query.html");
        registry.addViewController("/demo/dashboard").setViewName("forward:/demo/dashboard.html");
    }
}
