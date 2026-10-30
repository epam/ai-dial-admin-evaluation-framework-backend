package com.epam.aidial.evaluation.functional.config;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Real tracing without a network collector, isolated to tracing-enabled functional contexts. */
@TestConfiguration
@LogExecution
public class TracingFunctionalTestConfiguration {

    @Bean
    public InMemorySpanExporter tracingTestExporter() {
        return InMemorySpanExporter.create();
    }

    @Bean
    @Primary
    public OpenTelemetrySdk tracingTestOpenTelemetry(InMemorySpanExporter exporter) {
        return OpenTelemetrySdk.builder()
                .setPropagators(ContextPropagators.create(TextMapPropagator.composite(
                        W3CTraceContextPropagator.getInstance(), W3CBaggagePropagator.getInstance())))
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build();
    }
}
