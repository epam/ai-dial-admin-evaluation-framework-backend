package com.epam.aidial.evaluation.client.dialadas;

import com.epam.aidial.evaluation.client.dialadas.dto.AdasAggregateResponseDto;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasExecuteSqlRequestDto;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.net.SocketTimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP client for dial-adas's structured query DSL. Propagates the current user's token via
 * {@link com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder}. No retry loop: this is a
 * single user-facing read, not a background/critical-path call, so a failed attempt surfaces
 * immediately as a 502/504 rather than adding latency via retries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogExecution
public class DialAdasClient {

    private static final String QUERIES_EXECUTE_PATH = "/v1/queries/execute";
    private static final String EXECUTE_SQL_PATH = "/v1/queries/execute-sql";

    @Qualifier("dialAdasRestClient")
    private final RestClient dialAdasRestClient;

    public <T> AdasAggregateResponseDto<T> executeAggregate(StructuredQuery query, Class<T> rowType) {
        if (log.isDebugEnabled()) {
            log.debug("dial-adas request: POST {} -> {}", QUERIES_EXECUTE_PATH, query);
        }
        ParameterizedTypeReference<AdasAggregateResponseDto<T>> responseType =
                ParameterizedTypeReference.forType(TypeUtils.parameterize(AdasAggregateResponseDto.class, rowType));
        AdasAggregateResponseDto<T> response = post(QUERIES_EXECUTE_PATH, query, responseType);
        logResponse(QUERIES_EXECUTE_PATH, response);
        return response;
    }

    public <T> AdasAggregateResponseDto<T> executeSql(String sql, Class<T> rowType) {
        AdasExecuteSqlRequestDto body =
                AdasExecuteSqlRequestDto.builder().sql(sql).build();
        if (log.isDebugEnabled()) {
            log.debug("dial-adas request: POST {} -> {}", EXECUTE_SQL_PATH, body);
        }
        ParameterizedTypeReference<AdasAggregateResponseDto<T>> responseType =
                ParameterizedTypeReference.forType(TypeUtils.parameterize(AdasAggregateResponseDto.class, rowType));
        AdasAggregateResponseDto<T> response = post(EXECUTE_SQL_PATH, body, responseType);
        logResponse(EXECUTE_SQL_PATH, response);
        return response;
    }

    private static <T> void logResponse(String path, AdasAggregateResponseDto<T> response) {
        if (log.isDebugEnabled() && response != null) {
            log.debug("dial-adas response: POST {} -> {}", path, response);
        }
    }

    private <T> T post(String path, Object body, ParameterizedTypeReference<T> responseType) {
        try {
            return dialAdasRestClient
                    .post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(responseType);
        } catch (RestClientResponseException e) {
            throw new DialAdasClientException(e.getStatusCode().value(), e.getMessage(), e);
        } catch (ResourceAccessException e) {
            throw mapResourceAccessException(e);
        }
    }

    private static DialAdasClientException mapResourceAccessException(ResourceAccessException ex) {
        Throwable cause = getRootCause(ex);
        if (cause instanceof SocketTimeoutException) {
            return new DialAdasClientException(
                    HttpStatus.GATEWAY_TIMEOUT.value(), "dial-adas did not respond within the configured timeout", ex);
        }
        return new DialAdasClientException(
                HttpStatus.BAD_GATEWAY.value(), "Failed to connect to dial-adas: " + cause.getMessage(), ex);
    }

    private static Throwable getRootCause(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
