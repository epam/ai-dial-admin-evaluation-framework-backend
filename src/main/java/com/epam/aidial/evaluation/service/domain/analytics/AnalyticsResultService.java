package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.analytics.AnalyticsResultsProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.Cursor;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.CursorPage;
import com.epam.aidial.evaluation.data.db.analytics.repository.TestCaseRunResultRepository;
import com.epam.aidial.evaluation.data.db.exception.InvalidFilterException;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.CursorPageResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ResultCountResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.mapper.TestCaseRunResultMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class AnalyticsResultService {

    private final TestCaseRunResultRepository resultRepository;
    private final TestSuiteRunRepository runRepository;
    private final TestCaseRunResultMapper resultMapper;
    private final CursorCodec cursorCodec;
    private final AnalyticsResultsProperties analyticsProperties;
    private final FilterParser filterParser;

    @Transactional("analyticsTransactionManager")
    public BatchWriteResponseDto batchCreate(BatchWriteRequestDto request) {
        // Validate run existence and get created_at_ms
        TestSuiteRun run = runRepository
                .findById(request.getTestSuiteRunId())
                .orElseThrow(
                        () -> new EntityNotFoundException("Test suite run not found: " + request.getTestSuiteRunId()));

        // Validate suite ID matches
        if (!request.getTestSuiteId().equals(run.getTestSuiteId())) {
            throw new ValidationException("testSuiteId mismatch: request has " + request.getTestSuiteId()
                    + " but run belongs to " + run.getTestSuiteId());
        }

        // Validate batch size
        int maxItems = analyticsProperties.getBatch().getMaxItems();
        if (request.getResults().size() > maxItems) {
            throw new ValidationException(
                    "Batch size " + request.getResults().size() + " exceeds maximum of " + maxItems);
        }

        // Validate individual items
        for (TestCaseRunResultItemDto item : request.getResults()) {
            if (item.getExecutionInfo().getCompletedAt()
                    < item.getExecutionInfo().getStartedAt()) {
                throw new ValidationException(
                        "completedAt must be >= startedAt for test case '" + item.getTestCaseName() + "'");
            }
            if (!item.getTestCaseData().isObject()) {
                throw new ValidationException(
                        "testCaseData must be a JSON object for test case '" + item.getTestCaseName() + "'");
            }
        }

        long createdAtMs = run.getCreatedAt();

        List<TestCaseRunResult> entities = request.getResults().stream()
                .map(item ->
                        resultMapper.toEntity(item, request.getTestSuiteId(), request.getTestSuiteRunId(), createdAtMs))
                .toList();

        resultRepository.saveAll(entities);
        log.info("Batch created {} results for run {}", entities.size(), request.getTestSuiteRunId());

        return BatchWriteResponseDto.builder()
                .totalItems(request.getResults().size())
                .build();
    }

    @Transactional(value = "analyticsTransactionManager", readOnly = true)
    public CursorPageResponseDto<TestCaseRunResultResponseDto> listByFilter(
            List<String> filterParams, String cursorEncoded, int size) {
        List<FilterCondition> filters = filterParser.parse(filterParams);
        validateRequiredSuiteIdFilter(filters);

        Cursor cursor = cursorCodec.decode(cursorEncoded);
        Long runCreatedAtMs = resolveRunCreatedAtMs(filters);

        CursorPage<TestCaseRunResult> page;
        try {
            page = resultRepository.findAll(filters, runCreatedAtMs, cursor, size);
        } catch (InvalidFilterException ex) {
            throw new FilterValidationException(ex.getMessage(), ex.getDetails());
        }

        List<TestCaseRunResultResponseDto> content =
                page.content().stream().map(resultMapper::toDto).toList();

        String nextCursorEncoded = cursorCodec.encode(page.nextCursor());

        return CursorPageResponseDto.<TestCaseRunResultResponseDto>builder()
                .content(content)
                .size(size)
                .nextCursor(nextCursorEncoded)
                .hasMore(page.hasMore())
                .build();
    }

    @Transactional(value = "analyticsTransactionManager", readOnly = true)
    public TestCaseRunResultResponseDto getById(UUID id) {
        return resultRepository
                .findById(id)
                .map(resultMapper::toDto)
                .orElseThrow(() -> new EntityNotFoundException("Test case run result not found: " + id));
    }

    @Transactional(value = "analyticsTransactionManager", readOnly = true)
    public ResultCountResponseDto countByFilter(List<String> filterParams) {
        List<FilterCondition> filters = filterParser.parse(filterParams);
        validateRequiredSuiteIdFilter(filters);

        Long runCreatedAtMs = resolveRunCreatedAtMs(filters);
        long count;
        try {
            count = resultRepository.count(filters, runCreatedAtMs);
        } catch (InvalidFilterException ex) {
            throw new FilterValidationException(ex.getMessage(), ex.getDetails());
        }

        return ResultCountResponseDto.builder().count(count).build();
    }

    private void validateRequiredSuiteIdFilter(List<FilterCondition> filters) {
        boolean hasSuiteId = filters.stream().anyMatch(f -> "suiteId".equals(f.getField()));
        if (!hasSuiteId) {
            throw new ValidationException("Filter 'suiteId' is required");
        }
    }

    private Long resolveRunCreatedAtMs(List<FilterCondition> filters) {
        Optional<FilterCondition> runIdFilter =
                filters.stream().filter(f -> "runId".equals(f.getField())).findFirst();

        if (runIdFilter.isEmpty()) {
            return null;
        }

        UUID runId = UUID.fromString(runIdFilter.get().getRawValue());
        return runRepository
                .findById(runId)
                .map(TestSuiteRun::getCreatedAt)
                .orElse(null); // Orphan-safe: skip partition pruning if run not found
    }
}
