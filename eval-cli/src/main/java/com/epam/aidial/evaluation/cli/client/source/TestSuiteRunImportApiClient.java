package com.epam.aidial.evaluation.cli.client.source;

import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.io.File;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/** HTTP client for importing test-case run results into the source EF instance. */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class TestSuiteRunImportApiClient {

    @Qualifier("sourceRestClient")
    private final RestClient restClient;

    /**
     * Imports a CSV results file into the specified suite on the source EF via
     * {@code POST /api/v1/test-suites/{id}/runs/import}.
     *
     * <p>The import endpoint automatically triggers Phase 2/3 metric computation after a successful
     * import — the CLI must not issue a separate trigger.
     *
     * @param suiteId     the destination (cloned) suite UUID
     * @param csvFile     the results CSV file to upload
     * @param testRunName optional human-readable run name; {@code null} means no name override
     * @param delimiter   optional CSV delimiter character; {@code null} uses the server default ({@code ,})
     * @return the created run response DTO
     */
    public TestSuiteRunResponseDto importResults(UUID suiteId, File csvFile, String testRunName, String delimiter) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(csvFile));
        if (testRunName != null && !testRunName.isBlank()) {
            body.add("testRunName", testRunName);
        }
        if (delimiter != null) {
            body.add("delimiter", delimiter);
        }

        return restClient
                .post()
                .uri("/api/v1/test-suites/{id}/runs/import", suiteId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(TestSuiteRunResponseDto.class);
    }
}
