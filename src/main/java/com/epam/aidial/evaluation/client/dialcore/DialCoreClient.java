package com.epam.aidial.evaluation.client.dialcore;

import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreApplicationDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreApplicationListResponseDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreDeploymentDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreModelDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreModelListResponseDto;
import com.epam.aidial.evaluation.client.dialcore.dto.DialCoreToolsetDto;
import com.epam.aidial.evaluation.runner.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.config.properties.DialCoreProperties;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP client for DIAL Core API. Propagates the current user's token via
 * {@link com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogExecution
public class DialCoreClient {

    private static final String MODELS_PATH = "/openai/models";
    private static final String APPLICATIONS_PATH = "/openai/applications";
    private static final String DEPLOYMENTS_PATH = "/v1/deployments";
    private static final String TOOLSETS_PATH = "/openai/toolsets";
    private static final String SCHEMAS_PATH = "/v1/application_type_schemas/schema";
    private static final List<Integer> RETRYABLE_STATUS_CODES = List.of(408, 429, 500, 502, 503, 504);

    private static final TypeReference<List<DialCoreDeploymentDto>> DEPLOYMENT_LIST_TYPE = new TypeReference<>() {};

    @Qualifier("dialCoreRestClient")
    private final RestClient dialCoreRestClient;

    private final DialCoreProperties properties;
    private final ObjectMapper objectMapper;

    public DialCoreModelListResponseDto getModels() {
        return withRetry(MODELS_PATH, () -> get(MODELS_PATH, DialCoreModelListResponseDto.class));
    }

    public DialCoreApplicationListResponseDto getApplications() {
        return withRetry(APPLICATIONS_PATH, () -> get(APPLICATIONS_PATH, DialCoreApplicationListResponseDto.class));
    }

    public DialCoreModelDto getModel(String id) {
        return withRetry(MODELS_PATH + "/" + id, () -> get(MODELS_PATH + "/" + id, DialCoreModelDto.class));
    }

    public DialCoreApplicationDto getApplication(String id) {
        return withRetry(
                APPLICATIONS_PATH + "/" + id, () -> get(APPLICATIONS_PATH + "/" + id, DialCoreApplicationDto.class));
    }

    public List<DialCoreDeploymentDto> getDeployments(String interfaceType) {
        String path;
        if (interfaceType != null) {
            URI uri = UriComponentsBuilder.fromPath(DEPLOYMENTS_PATH)
                    .queryParam("interface_type", interfaceType)
                    .build()
                    .encode()
                    .toUri();
            path = uri.toASCIIString();
        } else {
            path = DEPLOYMENTS_PATH;
        }
        return withRetry(path, () -> {
            JsonNode body = get(path, JsonNode.class);
            if (body == null) {
                return List.of();
            }
            try {
                return objectMapper.convertValue(body, DEPLOYMENT_LIST_TYPE);
            } catch (IllegalArgumentException e) {
                throw new DialCoreClientException(
                        HttpStatusCode.valueOf(502),
                        "Failed to deserialize deployments response: " + e.getMessage(),
                        e);
            }
        });
    }

    public DialCoreToolsetDto getToolset(String id) {
        return withRetry(TOOLSETS_PATH + "/" + id, () -> get(TOOLSETS_PATH + "/" + id, DialCoreToolsetDto.class));
    }

    public JsonNode getApplicationTypeSchema(String schemaId) {
        URI uri = UriComponentsBuilder.fromPath(SCHEMAS_PATH)
                .queryParam("id", schemaId)
                .build()
                .encode()
                .toUri();
        String uriString = uri.toASCIIString();
        return withRetry(uriString, () -> get(uriString, JsonNode.class));
    }

    private <T> T get(String path, Class<T> responseType) {
        if (log.isDebugEnabled()) {
            log.debug("DIAL Core request: GET {}", path);
        }
        T body = dialCoreRestClient
                .get()
                .uri(path)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(responseType);
        if (log.isDebugEnabled() && body != null) {
            log.debug("DIAL Core response: GET {} -> {}", path, body);
        }
        return body;
    }

    private <T> T withRetry(String path, RequestSupplier<T> supplier) {
        int maxAttempts = properties.getRetry().getMaxAttempts();
        long delayMs = properties.getRetry().getDelayMs();
        double multiplier = properties.getRetry().getMultiplier();
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return supplier.get();
            } catch (RestClientResponseException e) {
                lastException = e;
                HttpStatusCode status = e.getStatusCode();
                if (!isRetryable(status) || attempt == maxAttempts) {
                    throw new DialCoreClientException(status, e.getMessage(), e.getResponseBodyAsString());
                }
                log.warn(
                        "DIAL Core request failed (attempt {}/{}), retrying in {} ms: GET {} - {}",
                        attempt,
                        maxAttempts,
                        delayMs,
                        path,
                        e.getMessage(),
                        e);
                sleep(delayMs);
                delayMs = (long) (delayMs * multiplier);
            }
        }

        if (lastException instanceof DialCoreClientException dce) {
            throw dce;
        }
        if (lastException instanceof RestClientResponseException e) {
            throw new DialCoreClientException(e.getStatusCode(), e.getMessage(), e.getResponseBodyAsString());
        }
        throw new DialCoreClientException(
                HttpStatusCode.valueOf(502),
                lastException != null ? lastException.getMessage() : "Unknown error",
                (Throwable) lastException);
    }

    private static boolean isRetryable(HttpStatusCode status) {
        if (status == null) {
            return false;
        }
        int code = status.value();
        return RETRYABLE_STATUS_CODES.contains(code);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DialCoreClientException(HttpStatusCode.valueOf(502), "Interrupted during retry", e);
        }
    }

    @FunctionalInterface
    private interface RequestSupplier<T> {
        T get();
    }
}
