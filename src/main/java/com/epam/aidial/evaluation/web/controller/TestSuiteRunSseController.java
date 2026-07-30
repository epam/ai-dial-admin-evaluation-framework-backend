package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.TestSuiteRunSseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@LogExecution
@RequiredArgsConstructor
@Tag(name = "Test Suite Runs", description = "Test Suite Run SSE endpoints")
public class TestSuiteRunSseController {

    private final TestSuiteRunSseService sseService;

    @GetMapping(value = "/api/v1/test-suite-runs/status-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
            summary = "Subscribe to run status updates",
            description = "SSE endpoint for real-time run status updates with optional filtering")
    @ApiResponse(responseCode = "200", description = "SSE stream established")
    public SseEmitter statusStream(
            @Parameter(description = "Comma-separated run IDs to filter") @RequestParam(required = false) String runIds,
            @Parameter(description = "Comma-separated test suite IDs to filter") @RequestParam(required = false)
                    String testSuiteIds,
            @Parameter(description = "Comma-separated statuses to filter") @RequestParam(required = false)
                    String statuses) {

        Set<UUID> runIdSet = parseUuids(runIds);
        Set<UUID> testSuiteIdSet = parseUuids(testSuiteIds);
        Set<String> statusSet = parseStrings(statuses);

        return sseService.createEmitter(runIdSet, testSuiteIdSet, statusSet);
    }

    private static Set<UUID> parseUuids(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(UUID::fromString)
                .collect(Collectors.toSet());
    }

    private static Set<String> parseStrings(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
