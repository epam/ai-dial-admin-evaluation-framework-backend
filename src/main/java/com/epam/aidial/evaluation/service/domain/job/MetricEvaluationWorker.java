package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.client.metricprovider.MetricProviderClient;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationRequestDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.MetricEvaluationProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.service.domain.dto.MetricParameterBindingDto;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Evaluates a single TSMD against a single test case result.
 * Acquires provider semaphore, resolves bindings, calls /evaluate, and retries on transient failures.
 * Throws on transport failure (after retries exhausted) — executor catches per-future.
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class MetricEvaluationWorker {

    private final MetricProviderClient metricProviderClient;
    private final BindingResolver bindingResolver;
    private final ExtractedColumnsNormalizer extractedColumnsNormalizer;
    private final OpenTelemetry openTelemetry;

    /**
     * Evaluates a single TSMD against a test case result.
     *
     * @param tsmd              the aggregated metric definition
     * @param result            the test case run result providing data for binding resolution
     * @param providerSemaphore semaphore controlling concurrency for this provider
     * @param context           metric evaluation context with retry config and cancellation signal
     * @return evaluation response from the metric provider
     * @throws RuntimeException on transport failure after retries exhausted
     * @throws InterruptedException if cancelled during backoff sleep
     */
    public EvaluationResponseDto evaluate(
            AggregatedMetricDefinition tsmd,
            TestCaseRunResult result,
            Semaphore providerSemaphore,
            MetricEvaluationContext context)
            throws InterruptedException {
        Span span = openTelemetry
                .getTracer("com.epam.aidial.evaluation")
                .spanBuilder("metric.tsmd.evaluate")
                .setAttribute("tsmd.name", tsmd.getName())
                .setAttribute("tsmd.provider.id", tsmd.getDeclarationProviderId())
                .setAttribute("eval.run.id", context.getTestSuiteRunId().toString())
                .setAttribute("result.id", result.getId().toString())
                .setAttribute("testcase.id", result.getTestCaseId().toString())
                .setAttribute("testcase.name", result.getTestCaseName())
                .setAttribute("eval.suite.id", context.getTestSuiteId().toString())
                .setAttribute("metric.declaration.name", tsmd.getMetricDeclarationName())
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            providerSemaphore.acquire();
            try {
                return invokeWithRetries(tsmd, result, context);
            } finally {
                providerSemaphore.release();
            }
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private EvaluationResponseDto invokeWithRetries(
            AggregatedMetricDefinition tsmd, TestCaseRunResult result, MetricEvaluationContext context)
            throws InterruptedException {
        EvaluationRequestDto request = buildRequest(tsmd, result);
        String providerId = tsmd.getDeclarationProviderId();
        MetricEvaluationProperties.Retry retryConfig = context.getRetryConfig();
        int maxRetries = retryConfig.getMaxRetries();

        Exception lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (context.getCancellationSignal().get()) {
                throw new InterruptedException("Metric evaluation cancelled");
            }

            if (attempt > 0) {
                long delay = computeBackoffDelay(attempt, retryConfig);
                log.debug(
                        "Retrying /evaluate for TSMD {} (attempt {}/{}), backoff {}ms",
                        tsmd.getName(),
                        attempt,
                        maxRetries,
                        delay);
                sleepWithCancellation(delay, context);
            }

            try {
                return metricProviderClient.evaluate(providerId, request);
            } catch (HttpClientErrorException.TooManyRequests e) {
                log.warn(
                        "Retryable 429 error for TSMD {} from provider {} (attempt {}/{}): {}",
                        tsmd.getName(),
                        providerId,
                        attempt,
                        maxRetries,
                        e.getMessage(),
                        e);
                lastException = e;
            } catch (HttpClientErrorException e) {
                log.warn(
                        "Non-retryable 4xx error for TSMD {} from provider {}: {} {}",
                        tsmd.getName(),
                        providerId,
                        e.getStatusCode(),
                        e.getMessage(),
                        e);
                throw e;
            } catch (HttpServerErrorException | ResourceAccessException e) {
                log.warn(
                        "Retryable error for TSMD {} from provider {} (attempt {}/{}): {}",
                        tsmd.getName(),
                        providerId,
                        attempt,
                        maxRetries,
                        e.getMessage(),
                        e);
                lastException = e;
            }
        }

        throw new RuntimeException(
                "Metric evaluation failed for TSMD " + tsmd.getName() + " after " + (maxRetries + 1) + " attempts: "
                        + lastException.getMessage(),
                lastException);
    }

    private EvaluationRequestDto buildRequest(AggregatedMetricDefinition tsmd, TestCaseRunResult result) {
        Map<String, Object> testCaseData = bindingResolver.parseJsonMap(result.getTestCaseData());
        // Multi-step results store extractedColumns as a per-step array; normalize to the last step's object
        // before resolving metric bindings. testCaseData is always an object and is left untouched.
        Map<String, Object> extractedColumns = bindingResolver.parseJsonMap(
                extractedColumnsNormalizer.normalizeToJsonString(result.getExtractedColumns()));

        List<MetricParameterBindingDto> configBindings = bindingResolver.parseBindings(tsmd.getConfigBindings());
        List<MetricParameterBindingDto> inputBindings = bindingResolver.parseBindings(tsmd.getInputBindings());

        Map<String, Object> config = bindingResolver.resolveBindings(configBindings, testCaseData, extractedColumns);
        Map<String, Object> input = bindingResolver.resolveBindings(inputBindings, testCaseData, extractedColumns);

        return EvaluationRequestDto.builder()
                .metricName(tsmd.getMetricDeclarationName())
                .config(config)
                .input(input)
                .build();
    }

    private void sleepWithCancellation(long delayMs, MetricEvaluationContext context) throws InterruptedException {
        long remaining = delayMs;
        long step = Math.min(remaining, 500L);
        while (remaining > 0) {
            if (context.getCancellationSignal().get()) {
                throw new InterruptedException("Metric evaluation cancelled during backoff");
            }
            Thread.sleep(Math.min(step, remaining));
            remaining -= step;
        }
    }

    private long computeBackoffDelay(int attempt, MetricEvaluationProperties.Retry retryConfig) {
        long delay =
                (long) (retryConfig.getRetryDelayMs() * Math.pow(retryConfig.getRetryBackoffMultiplier(), attempt - 1));
        return Math.min(delay, retryConfig.getMaxRetryDelayMs());
    }
}
