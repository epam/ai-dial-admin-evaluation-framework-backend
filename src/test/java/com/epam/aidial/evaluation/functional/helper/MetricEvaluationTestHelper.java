package com.epam.aidial.evaluation.functional.helper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.client.metricprovider.MetricProviderClient;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationRequestDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpHeaders;

/**
 * Encapsulates mock setup for {@link DialCoreDeploymentInvoker} and {@link MetricProviderClient}
 * in metric evaluation functional tests. Provides a fluent API for configuring per-test-case
 * deployment behavior and metric evaluation responses.
 */
public class MetricEvaluationTestHelper {

    private final DialCoreDeploymentInvoker deploymentInvoker;
    private final MetricProviderClient metricProviderClient;

    private DeploymentInvocationResult defaultDeploymentResult;
    private int failAtCallNumber = -1;
    private RuntimeException deploymentFailureException;
    private final Map<String, EvaluationResponseDto> metricResponses = new LinkedHashMap<>();

    public MetricEvaluationTestHelper(
            DialCoreDeploymentInvoker deploymentInvoker, MetricProviderClient metricProviderClient) {
        this.deploymentInvoker = deploymentInvoker;
        this.metricProviderClient = metricProviderClient;
    }

    /**
     * Configures the deployment mock to return a SUCCESS response with the given body.
     */
    public MetricEvaluationTestHelper withDeploymentSuccess(Object responseBody) {
        this.defaultDeploymentResult =
                new DeploymentInvocationResult(200, false, responseBody, null, new HttpHeaders());
        return this;
    }

    /**
     * Configures the deployment mock to throw on the Nth invocation (1-based).
     * All other invocations return the default success response.
     */
    public MetricEvaluationTestHelper withDeploymentFailureOnCall(int callNumber, RuntimeException exception) {
        this.failAtCallNumber = callNumber;
        this.deploymentFailureException = exception;
        return this;
    }

    /**
     * Configures the metric provider mock to return the given response for the given metric name.
     */
    public MetricEvaluationTestHelper withMetricResponse(String metricName, EvaluationResponseDto response) {
        this.metricResponses.put(metricName, response);
        return this;
    }

    /**
     * Applies the configured mocks to the injected invoker and client.
     */
    public void apply() {
        setupDeploymentMock();
        if (!metricResponses.isEmpty()) {
            setupMetricProviderMock();
        }
    }

    private void setupDeploymentMock() {
        if (failAtCallNumber > 0) {
            AtomicInteger counter = new AtomicInteger(0);
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        if (counter.incrementAndGet() == failAtCallNumber) {
                            throw deploymentFailureException;
                        }
                        return defaultDeploymentResult;
                    });
        } else {
            when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                    .thenReturn(defaultDeploymentResult);
        }
    }

    private void setupMetricProviderMock() {
        when(metricProviderClient.evaluate(anyString(), any(EvaluationRequestDto.class)))
                .thenAnswer(invocation -> {
                    EvaluationRequestDto request = invocation.getArgument(1);
                    EvaluationResponseDto response = metricResponses.get(request.getMetricName());
                    if (response == null) {
                        throw new IllegalStateException(
                                "No mock metric response configured for: " + request.getMetricName());
                    }
                    return response;
                });
    }
}
