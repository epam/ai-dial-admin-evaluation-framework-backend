package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.analytics.EvalSummaryProperties;
import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricAggregationResult;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricPath;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.Cursor;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.CursorPage;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.exception.InvalidFilterException;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.service.domain.dto.analytics.CursorPageResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryDetailResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.MetricAggregationItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.MetricAggregationResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ResultCountResponseDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import com.epam.aidial.evaluation.service.domain.mapper.EvalSummaryMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class EvalSummaryService {

    private final EvalSummaryRepository evalSummaryRepository;
    private final TestSuiteRunRepository runRepository;
    private final EvalSummaryMapper evalSummaryMapper;
    private final CursorCodec cursorCodec;
    private final EvalSummaryProperties evalSummaryProperties;
    private final FilterParser filterParser;
    private final ComputationResolver computationResolver;

    @Transactional("analyticsTransactionManager")
    public EvalSummaryBatchWriteResponseDto batchCreate(EvalSummaryBatchWriteRequestDto request) {
        TestSuiteRun run = runRepository
                .findById(request.getTestSuiteRunId())
                .orElseThrow(
                        () -> new EntityNotFoundException("Test suite run not found: " + request.getTestSuiteRunId()));

        if (!request.getTestSuiteId().equals(run.getTestSuiteId())) {
            throw new ValidationException("testSuiteId mismatch: request has " + request.getTestSuiteId()
                    + " but run belongs to " + run.getTestSuiteId());
        }

        int maxItems = evalSummaryProperties.getBatch().getMaxItems();
        if (request.getItems().size() > maxItems) {
            throw new ValidationException(
                    "Batch size " + request.getItems().size() + " exceeds maximum of " + maxItems);
        }

        for (EvalSummaryBatchWriteItemDto item : request.getItems()) {
            if (!item.getTestCaseData().isObject()) {
                throw new ValidationException(
                        "testCaseData must be a JSON object for test case '" + item.getTestCaseName() + "'");
            }
            validateMetricValues(item.getMetricValues(), item.getTestCaseName());
        }

        long createdAtMs = run.getCreatedAt();

        List<EvalSummary> entities = request.getItems().stream()
                .map(item -> evalSummaryMapper.toEntity(
                        item,
                        request.getTestSuiteId(),
                        request.getTestSuiteRunId(),
                        request.getComputationId(),
                        createdAtMs,
                        request.getComputedAtMs()))
                .toList();

        evalSummaryRepository.saveAll(entities);
        log.info("Batch created {} eval summaries for run {}", entities.size(), request.getTestSuiteRunId());

        return EvalSummaryBatchWriteResponseDto.builder()
                .totalItems(request.getItems().size())
                .build();
    }

    @Transactional(value = "analyticsTransactionManager", readOnly = true)
    public CursorPageResponseDto<EvalSummaryResponseDto> listByFilter(
            List<String> filterParams, String computation, String cursorEncoded, int size) {
        List<FilterCondition> filters = filterParser.parse(filterParams);
        validateRequiredRunIdFilter(filters);

        UUID runId = extractRunId(filters);
        UUID computationId = resolveComputationId(computation, runId);
        if (computationId == null) {
            return emptyPage(size);
        }

        Cursor cursor = cursorCodec.decode(cursorEncoded);
        Long runCreatedAtMs = resolveRunCreatedAtMs(runId);

        CursorPage<EvalSummary> page;
        try {
            page = evalSummaryRepository.findAll(filters, computationId, runCreatedAtMs, cursor, size);
        } catch (InvalidFilterException ex) {
            throw new FilterValidationException(ex.getMessage(), ex.getDetails());
        }

        List<EvalSummaryResponseDto> content =
                page.content().stream().map(evalSummaryMapper::toDto).toList();

        String nextCursorEncoded = cursorCodec.encode(page.nextCursor());

        return CursorPageResponseDto.<EvalSummaryResponseDto>builder()
                .content(content)
                .size(size)
                .nextCursor(nextCursorEncoded)
                .hasMore(page.hasMore())
                .build();
    }

    @Transactional(value = "analyticsTransactionManager", readOnly = true)
    public EvalSummaryDetailResponseDto getById(UUID id) {
        return evalSummaryRepository
                .findById(id)
                .map(evalSummaryMapper::toDetailDto)
                .orElseThrow(() -> new EntityNotFoundException("Eval summary not found: " + id));
    }

    @Transactional(value = "analyticsTransactionManager", readOnly = true)
    public ResultCountResponseDto countByFilter(List<String> filterParams, String computation) {
        List<FilterCondition> filters = filterParser.parse(filterParams);
        validateRequiredRunIdFilter(filters);

        UUID runId = extractRunId(filters);
        UUID computationId = resolveComputationId(computation, runId);
        if (computationId == null) {
            return ResultCountResponseDto.builder().count(0).build();
        }

        Long runCreatedAtMs = resolveRunCreatedAtMs(runId);

        long count;
        try {
            count = evalSummaryRepository.count(filters, computationId, runCreatedAtMs);
        } catch (InvalidFilterException ex) {
            throw new FilterValidationException(ex.getMessage(), ex.getDetails());
        }

        return ResultCountResponseDto.builder().count(count).build();
    }

    @Transactional(value = "analyticsTransactionManager", readOnly = true)
    public MetricAggregationResponseDto aggregate(
            List<String> filterParams, String computation, List<String> metricPaths) {
        List<FilterCondition> filters = filterParser.parse(filterParams);
        validateRequiredRunIdFilter(filters);

        UUID runId = extractRunId(filters);
        UUID computationId = resolveComputationId(computation, runId);
        if (computationId == null) {
            return MetricAggregationResponseDto.builder()
                    .computationId(null)
                    .metrics(List.of())
                    .build();
        }

        List<MetricPath> parsedMetricPaths = parseMetricPaths(metricPaths);
        Long runCreatedAtMs = resolveRunCreatedAtMs(runId);

        List<MetricAggregationResult> results;
        try {
            results = evalSummaryRepository.aggregate(filters, computationId, runCreatedAtMs, parsedMetricPaths);
        } catch (InvalidFilterException ex) {
            throw new FilterValidationException(ex.getMessage(), ex.getDetails());
        }

        List<MetricAggregationItemDto> items = results.stream()
                .map(r -> MetricAggregationItemDto.builder()
                        .metric(r.metricName())
                        .output(r.outputName())
                        .avg(r.avg())
                        .min(r.min())
                        .max(r.max())
                        .count(r.count())
                        .build())
                .toList();

        return MetricAggregationResponseDto.builder()
                .computationId(computationId)
                .metrics(items)
                .build();
    }

    private void validateRequiredRunIdFilter(List<FilterCondition> filters) {
        boolean hasRunId = filters.stream().anyMatch(f -> "runId".equals(f.getField()));
        if (!hasRunId) {
            throw new ValidationException("Filter 'runId' is required");
        }
    }

    private UUID extractRunId(List<FilterCondition> filters) {
        return filters.stream()
                .filter(f -> "runId".equals(f.getField()))
                .findFirst()
                .map(f -> UUID.fromString(f.getRawValue()))
                .orElseThrow(() -> new ValidationException("Filter 'runId' is required"));
    }

    private UUID resolveComputationId(String computation, UUID runId) {
        return computationResolver.resolve(computation, runId).orElse(null);
    }

    private Long resolveRunCreatedAtMs(UUID runId) {
        return runRepository.findById(runId).map(TestSuiteRun::getCreatedAt).orElse(null);
    }

    private List<MetricPath> parseMetricPaths(List<String> metricPaths) {
        List<MetricPath> result = new ArrayList<>(metricPaths.size());
        for (String path : metricPaths) {
            int dotIndex = path.indexOf('.');
            if (dotIndex <= 0 || dotIndex >= path.length() - 1) {
                throw new ValidationException(
                        "Invalid metric path '" + path + "': expected format 'MetricName.outputName'");
            }
            String metricName = path.substring(0, dotIndex);
            String outputName = path.substring(dotIndex + 1);
            result.add(new MetricPath(metricName, outputName));
        }
        return result;
    }

    private void validateMetricValues(JsonNode metricValues, String testCaseName) {
        if (!metricValues.isObject()) {
            throw new ValidationException("metricValues must be a JSON object for test case '" + testCaseName + "'");
        }
        Iterator<Map.Entry<String, JsonNode>> metricFields = metricValues.fields();
        while (metricFields.hasNext()) {
            Map.Entry<String, JsonNode> metricEntry = metricFields.next();
            String metricName = metricEntry.getKey();
            JsonNode metricOutputs = metricEntry.getValue();

            if (!metricOutputs.isObject()) {
                throw new ValidationException("metricValues['" + metricName + "'] must be a JSON object for test case '"
                        + testCaseName + "'");
            }

            Iterator<Map.Entry<String, JsonNode>> outputFields = metricOutputs.fields();
            while (outputFields.hasNext()) {
                Map.Entry<String, JsonNode> outputEntry = outputFields.next();
                JsonNode value = outputEntry.getValue();
                if (!value.isNumber() && !value.isNull()) {
                    throw new ValidationException("metricValues['" + metricName + "']['" + outputEntry.getKey()
                            + "'] must be numeric or null for test case '" + testCaseName + "'");
                }
            }
        }
    }

    private CursorPageResponseDto<EvalSummaryResponseDto> emptyPage(int size) {
        return CursorPageResponseDto.<EvalSummaryResponseDto>builder()
                .content(List.of())
                .size(size)
                .nextCursor(null)
                .hasMore(false)
                .build();
    }
}
