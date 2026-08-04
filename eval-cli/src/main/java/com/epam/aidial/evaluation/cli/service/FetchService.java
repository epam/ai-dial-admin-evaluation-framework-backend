package com.epam.aidial.evaluation.cli.service;

import com.epam.aidial.evaluation.cli.client.source.TestCaseApiClient;
import com.epam.aidial.evaluation.cli.client.source.TestSuiteApiClient;
import com.epam.aidial.evaluation.cli.client.source.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.cli.config.properties.EvalCliProperties;
import com.epam.aidial.evaluation.cli.model.SuiteFetchBundle;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Fetches a suite's configuration and test cases from the source EF and persists the bundle as a
 * JSON file under {@code cli.workDir} so the {@code run} step can be invoked standalone against
 * previously fetched data.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class FetchService {

    private final TestSuiteApiClient testSuiteApiClient;
    private final TestCaseApiClient testCaseApiClient;
    private final EvalCliProperties cliProperties;
    private final ObjectMapper objectMapper;

    /**
     * Fetches the suite config and all test cases for the given source suite, then persists the
     * bundle to {@code <workDir>/<sourceSuiteId>.json}.
     *
     * @param sourceSuiteId     the source suite UUID to fetch
     * @param destinationSuiteId the clone ID that will receive imported results (stored in the bundle)
     * @return the persisted {@link SuiteFetchBundle}
     * @throws IllegalStateException if the source suite cannot be found
     * @throws RuntimeException      if bundle persistence fails
     */
    public SuiteFetchBundle fetch(UUID sourceSuiteId, UUID destinationSuiteId) {
        final TestSuiteResponseDto suite = testSuiteApiClient
                .findById(sourceSuiteId)
                .orElseThrow(() -> new IllegalStateException("Source suite not found: " + sourceSuiteId));

        final List<TestCaseResponseDto> testCases = testCaseApiClient.fetchAll(suite.getDatasetId());
        log.info("Fetched suite '{}' ({}) with {} test case(s)", suite.getName(), sourceSuiteId, testCases.size());

        final SuiteFetchBundle bundle = SuiteFetchBundle.builder()
                .sourceSuiteId(sourceSuiteId)
                .destinationSuiteId(destinationSuiteId)
                .suite(suite)
                .testCases(testCases)
                .build();

        persist(sourceSuiteId, bundle);
        return bundle;
    }

    /**
     * Loads a previously persisted bundle from {@code <workDir>/<sourceSuiteId>.json}.
     *
     * @param sourceSuiteId the suite UUID whose bundle to load
     * @return the deserialized bundle
     * @throws RuntimeException if the file cannot be read
     */
    public SuiteFetchBundle load(UUID sourceSuiteId) {
        final Path path = bundlePath(sourceSuiteId);
        try {
            return objectMapper.readValue(path.toFile(), SuiteFetchBundle.class);
        } catch (JacksonException e) {
            throw new RuntimeException(
                    "Failed to load fetch bundle for suite " + sourceSuiteId + " from " + path + ": " + e.getMessage(),
                    e);
        }
    }

    private void persist(UUID sourceSuiteId, SuiteFetchBundle bundle) {
        final Path path = bundlePath(sourceSuiteId);
        try {
            Files.createDirectories(path.getParent());
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to create work directory for bundle " + sourceSuiteId + ": " + e.getMessage(), e);
        }
        try {
            objectMapper.writeValue(path.toFile(), bundle);
            log.debug("Persisted fetch bundle to {}", path);
        } catch (JacksonException e) {
            throw new RuntimeException(
                    "Failed to persist fetch bundle for suite " + sourceSuiteId + ": " + e.getMessage(), e);
        }
    }

    private Path bundlePath(UUID sourceSuiteId) {
        return Path.of(cliProperties.getWorkDir(), sourceSuiteId + ".json");
    }
}
