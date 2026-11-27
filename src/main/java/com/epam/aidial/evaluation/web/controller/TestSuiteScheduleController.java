package com.epam.aidial.evaluation.web.controller;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import com.epam.aidial.evaluation.service.domain.TestSuiteScheduleService;
import com.epam.aidial.evaluation.service.domain.dto.ScheduleRunDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteScheduleRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteScheduleResponseDto;
import com.epam.aidial.evaluation.web.pagination.PaginationParamResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manages cron schedules that run a test suite, delegated to the DIAL Core scheduler application. Registered only
 * when {@code dial.scheduler.enabled=true}; otherwise none of these routes exist.
 */
@RestController
@LogExecution
@Validated
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dial.scheduler.enabled", havingValue = "true")
@Tag(
        name = "Test Suite Schedules",
        description = "Cron schedules that run a test suite, backed by the DIAL Core scheduler application")
public class TestSuiteScheduleController {

    private static final String PAGE_DESCRIPTION = "Page number (0-based)";
    private static final String SIZE_DESCRIPTION = "Page size";

    private final TestSuiteScheduleService scheduleService;
    private final PaginationParamResolver paginationParamResolver;

    @PostMapping(
            value = "/api/v1/test-suites/{testSuiteId}/schedules",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Create a schedule for a test suite",
            description = "Registers a cron schedule in the DIAL Core scheduler that starts a run of the suite with "
                    + "the given run configuration. Cron fields use APScheduler syntax and are interpreted in the "
                    + "given IANA timezone (default UTC).")
    @ApiResponse(responseCode = "201", description = "Schedule created")
    @ApiResponse(responseCode = "400", description = "Invalid cron, timezone or run configuration")
    @ApiResponse(responseCode = "404", description = "Test suite not found")
    public TestSuiteScheduleResponseDto create(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId,
            @Valid @RequestBody TestSuiteScheduleRequestDto request,
            @AuthenticationPrincipal Jwt jwt) {
        return scheduleService.create(testSuiteId, request, jwt);
    }

    @GetMapping(value = "/api/v1/test-suites/{testSuiteId}/schedules", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List the schedules of a test suite",
            description = "Returns the suite's schedules, newest first. A schedule that was removed from the "
                    + "scheduler outside this service is omitted, so a page can hold fewer items than its size.")
    @ApiResponse(responseCode = "200", description = "Page of schedules")
    @ApiResponse(responseCode = "404", description = "Test suite not found")
    public PageResponseDto<TestSuiteScheduleResponseDto> list(
            @Parameter(description = "Test suite ID") @PathVariable UUID testSuiteId,
            @Parameter(description = PAGE_DESCRIPTION) @RequestParam(required = false) Integer page,
            @Parameter(description = SIZE_DESCRIPTION) @RequestParam(required = false) Integer size) {
        return scheduleService.listForSuite(
                testSuiteId, paginationParamResolver.resolvePage(page), paginationParamResolver.resolveSize(size));
    }

    @GetMapping(value = "/api/v1/schedules/{scheduleId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a schedule")
    @ApiResponse(responseCode = "200", description = "Schedule")
    @ApiResponse(responseCode = "404", description = "Schedule not found")
    public TestSuiteScheduleResponseDto get(
            @Parameter(description = "Schedule ID") @PathVariable("scheduleId") String scheduleId) {
        return scheduleService.get(scheduleId);
    }

    @PutMapping(
            value = "/api/v1/schedules/{scheduleId}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Edit a schedule",
            description = "Replaces the name, description, cron, timezone and run configuration. The suite a "
                    + "schedule runs cannot be changed.")
    @ApiResponse(responseCode = "200", description = "Schedule updated")
    @ApiResponse(responseCode = "400", description = "Invalid cron, timezone or run configuration")
    @ApiResponse(responseCode = "404", description = "Schedule not found")
    public TestSuiteScheduleResponseDto update(
            @Parameter(description = "Schedule ID") @PathVariable("scheduleId") String scheduleId,
            @Valid @RequestBody TestSuiteScheduleRequestDto request) {
        return scheduleService.update(scheduleId, request);
    }

    @PostMapping(value = "/api/v1/schedules/{scheduleId}/pause", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Pause a schedule", description = "Stops future firings; runs already started are unaffected.")
    @ApiResponse(responseCode = "200", description = "Schedule paused")
    @ApiResponse(responseCode = "404", description = "Schedule not found")
    public TestSuiteScheduleResponseDto pause(
            @Parameter(description = "Schedule ID") @PathVariable("scheduleId") String scheduleId) {
        return scheduleService.pause(scheduleId);
    }

    @PostMapping(value = "/api/v1/schedules/{scheduleId}/resume", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Resume a paused schedule")
    @ApiResponse(responseCode = "200", description = "Schedule resumed")
    @ApiResponse(responseCode = "404", description = "Schedule not found")
    public TestSuiteScheduleResponseDto resume(
            @Parameter(description = "Schedule ID") @PathVariable("scheduleId") String scheduleId) {
        return scheduleService.resume(scheduleId);
    }

    @DeleteMapping("/api/v1/schedules/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Delete a schedule",
            description = "Removes the schedule from the scheduler. Runs it already started are kept.")
    @ApiResponse(responseCode = "204", description = "Schedule deleted")
    @ApiResponse(responseCode = "404", description = "Schedule not found")
    public void delete(@Parameter(description = "Schedule ID") @PathVariable("scheduleId") String scheduleId) {
        scheduleService.delete(scheduleId);
    }

    @GetMapping(value = "/api/v1/schedules/{scheduleId}/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "List the firings of a schedule",
            description = "Fire history as recorded by the scheduler, newest first.")
    @ApiResponse(responseCode = "200", description = "Page of firings")
    @ApiResponse(responseCode = "404", description = "Schedule not found")
    public PageResponseDto<ScheduleRunDto> listRuns(
            @Parameter(description = "Schedule ID") @PathVariable("scheduleId") String scheduleId,
            @Parameter(description = PAGE_DESCRIPTION) @RequestParam(required = false) Integer page,
            @Parameter(description = SIZE_DESCRIPTION) @RequestParam(required = false) Integer size) {
        return scheduleService.listRuns(
                scheduleId, paginationParamResolver.resolvePage(page), paginationParamResolver.resolveSize(size));
    }
}
