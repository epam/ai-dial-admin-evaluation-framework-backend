package com.epam.aidial.evaluation.client.metricprovider;

import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationRequestDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricsResponseDto;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls metric provider endpoints (GET /metrics, POST /evaluate).
 * Non-2xx and timeouts propagate so the caller can log and continue.
 * No user token propagation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@LogExecution
public class MetricProviderClient {

    private static final String METRICS_PATH = "/metrics";
    private static final String EVALUATE_PATH = "/evaluate";

    private final MetricProviderRestClientFactory restClientFactory;

    /**
     * Fetches GET /metrics from the provider with the given id.
     *
     * @param providerId configured provider id (must have a RestClient in the factory)
     * @return parsed MetricsResponseDto
     * @throws org.springframework.web.client.RestClientException on non-2xx, timeout, or parse error
     * @throws IllegalArgumentException if no RestClient is configured for providerId
     */
    public MetricsResponseDto getMetrics(String providerId) {
        RestClient client = restClientFactory
                .getRestClient(providerId)
                .orElseThrow(() ->
                        new IllegalArgumentException("No RestClient configured for metric provider: " + providerId));
        if (log.isDebugEnabled()) {
            log.debug("Metric provider request: GET {} for provider {}", METRICS_PATH, providerId);
        }
        MetricsResponseDto body = client.get()
                .uri(METRICS_PATH)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(MetricsResponseDto.class);
        if (log.isDebugEnabled() && body != null) {
            log.debug(
                    "Metric provider response: GET {} for provider {} -> {} metrics",
                    METRICS_PATH,
                    providerId,
                    body.getMetrics() != null ? body.getMetrics().size() : 0);
        }
        return body;
    }

    /**
     * Calls POST /evaluate on the provider with the given id.
     *
     * @param providerId configured provider id (must have a RestClient in the factory)
     * @param request    evaluation request (metric_name, config, input)
     * @return parsed EvaluationResponseDto
     * @throws org.springframework.web.client.RestClientException on non-2xx, timeout, or parse error
     * @throws IllegalArgumentException if no RestClient is configured for providerId
     */
    public EvaluationResponseDto evaluate(String providerId, EvaluationRequestDto request) {
        RestClient client = restClientFactory
                .getRestClient(providerId)
                .orElseThrow(() ->
                        new IllegalArgumentException("No RestClient configured for metric provider: " + providerId));
        if (log.isDebugEnabled()) {
            log.debug(
                    "Metric provider request: POST {} for provider {}, metric_name={}",
                    EVALUATE_PATH,
                    providerId,
                    request.getMetricName());
        }
        EvaluationResponseDto body = client.post()
                .uri(EVALUATE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(EvaluationResponseDto.class);
        if (log.isDebugEnabled() && body != null) {
            log.debug(
                    "Metric provider response: POST {} for provider {}, metric_name={} -> {} output fields",
                    EVALUATE_PATH,
                    providerId,
                    request.getMetricName(),
                    body.getOutput() != null ? body.getOutput().size() : 0);
        }
        return body;
    }
}
