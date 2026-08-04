package com.epam.aidial.evaluation.cli.service;

import com.epam.aidial.evaluation.cli.client.source.TestSuiteRunImportApiClient;
import com.epam.aidial.evaluation.cli.client.source.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.cli.config.properties.EvalCliProperties;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.io.File;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Imports a results CSV into the destination (cloned) suite on the source EF.
 *
 * <p>The source EF's import endpoint automatically dispatches Phase 2/3 metric computation after a
 * successful import. This service issues no additional trigger — one import call is all that is needed.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class ImportService {

    private final TestSuiteRunImportApiClient importApiClient;
    private final EvalCliProperties cliProperties;

    /**
     * Imports the given results CSV into the destination suite.
     *
     * @param destinationSuiteId the clone suite UUID to import into
     * @param csvFile            the results CSV file produced by the {@code run} step
     * @return the created run's response DTO
     */
    public TestSuiteRunResponseDto importResults(UUID destinationSuiteId, File csvFile) {
        log.info("Importing results from {} into destination suite {}", csvFile.getName(), destinationSuiteId);

        final TestSuiteRunResponseDto runDto =
                importApiClient.importResults(destinationSuiteId, csvFile, cliProperties.getTestRunName(), null);

        log.info(
                "Import complete — created run {} (status={}) for suite {}",
                runDto.getId(),
                runDto.getStatus(),
                destinationSuiteId);
        return runDto;
    }
}
