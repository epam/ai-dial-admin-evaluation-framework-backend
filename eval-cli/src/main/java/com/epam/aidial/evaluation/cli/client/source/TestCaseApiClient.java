package com.epam.aidial.evaluation.cli.client.source;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** HTTP client for fetching test cases from the source EF instance. */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class TestCaseApiClient {

    private static final int PAGE_SIZE = 100;

    @Qualifier("sourceRestClient")
    private final RestClient restClient;

    /**
     * Fetches all test cases for the given dataset by paginating through the source EF's
     * {@code GET /api/v1/datasets/{datasetId}/test-cases} endpoint.
     *
     * @param datasetId the dataset UUID whose test cases to fetch
     * @return the full, materialized list of test cases (may be large)
     */
    public List<TestCaseResponseDto> fetchAll(UUID datasetId) {
        final List<TestCaseResponseDto> all = new ArrayList<>();
        int page = 0;

        while (true) {
            final int currentPage = page;
            final PageResponseDto<TestCaseResponseDto> response = restClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/datasets/{datasetId}/test-cases")
                            .queryParam("page", currentPage)
                            .queryParam("size", PAGE_SIZE)
                            .build(datasetId))
                    .retrieve()
                    .body(new ParameterizedTypeReference<PageResponseDto<TestCaseResponseDto>>() {});

            if (response == null
                    || response.getContent() == null
                    || response.getContent().isEmpty()) {
                break;
            }

            all.addAll(response.getContent());

            if (response.getContent().size() < PAGE_SIZE) {
                // Last page
                break;
            }

            page++;
        }

        log.debug("Fetched {} test case(s) for dataset {}", all.size(), datasetId);
        return all;
    }
}
