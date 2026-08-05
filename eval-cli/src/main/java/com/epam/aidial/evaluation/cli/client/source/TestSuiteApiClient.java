package com.epam.aidial.evaluation.cli.client.source;

import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteUpdateResultDto;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteCloneRequestDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** HTTP client for test-suite operations on the source EF instance. */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class TestSuiteApiClient {

    private static final int PAGE_SIZE = 100;

    @Qualifier("sourceRestClient")
    private final RestClient restClient;

    /**
     * Retrieves a test suite by its ID.
     *
     * @param suiteId the suite UUID
     * @return the suite, or empty when not found (404)
     * @throws org.springframework.web.client.RestClientException on non-404 HTTP errors
     */
    public Optional<TestSuiteResponseDto> findById(UUID suiteId) {
        try {
            final TestSuiteResponseDto suite = restClient
                    .get()
                    .uri("/api/v1/test-suites/{id}", suiteId)
                    .retrieve()
                    .body(TestSuiteResponseDto.class);
            return Optional.ofNullable(suite);
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("Suite {} not found on source EF: {}", suiteId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Searches for a suite with an exact name match on the first page.
     *
     * <p>Used only by the clone idempotency check — returns the first match (if any) from a
     * {@code ?filter=name:eq:<name>} query. Names are assumed unique enough for this purpose.
     *
     * @param exactName the exact suite name to search for
     * @return the matching suite, or empty when none found
     */
    public Optional<TestSuiteResponseDto> findByExactName(String exactName) {
        try {
            final PageResponseDto<TestSuiteResponseDto> page = restClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/test-suites")
                            .queryParam("filter", "name:eq:" + exactName)
                            .queryParam("size", "1")
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<PageResponseDto<TestSuiteResponseDto>>() {});
            if (page == null || page.getContent() == null || page.getContent().isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(page.getContent().get(0));
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("No suite found with name '{}': {}", exactName, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Creates a clone of the given source suite.
     *
     * <p>The endpoint responds with a {@code TestSuiteUpdateResultDto} envelope ({@code {"suite":
     * {...}, "revalidationTask": null}}), not a bare {@code TestSuiteResponseDto} — the cloned
     * suite is nested under {@code suite}.
     *
     * @param sourceSuiteId the UUID of the suite to clone
     * @param request       the clone request (must include a non-blank {@code name})
     * @return the newly created clone's response DTO
     * @throws org.springframework.web.client.RestClientException on HTTP errors
     */
    public TestSuiteResponseDto clone(UUID sourceSuiteId, TestSuiteCloneRequestDto request) {
        final TestSuiteUpdateResultDto result = restClient
                .post()
                .uri("/api/v1/test-suites/{id}/clone", sourceSuiteId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(TestSuiteUpdateResultDto.class);
        return result != null ? result.getSuite() : null;
    }
}
