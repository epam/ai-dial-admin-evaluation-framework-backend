package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.csv.CsvExportProperties;
import com.epam.aidial.evaluation.constants.ValidationConstants;
import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import com.epam.aidial.evaluation.data.db.analytics.model.RunMetricSnapshot;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.Cursor;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.CursorPage;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.data.db.analytics.repository.RunMetricSnapshotRepository;
import com.epam.aidial.evaluation.data.db.exception.InvalidFilterException;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.service.domain.csv.CsvDelimiterParser;
import com.epam.aidial.evaluation.service.domain.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryExportRequestDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import com.epam.aidial.evaluation.service.domain.exception.RunNotTerminalException;
import com.epam.aidial.evaluation.service.domain.exception.SnapshotSuiteMissingException;
import com.epam.aidial.evaluation.service.domain.exception.UnsupportedSnapshotVersionException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Streams an {@link EvalSummary} export as CSV and produces a JSON preview of the column manifest
 * plus the first ≤10 rows.
 *
 * <p><b>Transaction strategy</b> (design D9.1): this service deliberately does NOT carry a
 * class-level {@code @Transactional} annotation. Setup runs in one short meta tx + one short
 * analytics tx; the streaming phase wraps each repository page in its own short analytics tx so
 * per-page commits release the analytics connection between pages and the HTTP write happens
 * outside any transaction.
 */
@Slf4j
@Service
@LogExecution
public class EvalSummaryExportService {

    private static final String LATEST_SENTINEL = "latest";
    private static final String RUN_ID_FILTER_FIELD = "runId";
    private static final int PREVIEW_PAGE_SIZE = 10;

    private final TestSuiteRunRepository runRepository;
    private final RunMetricSnapshotRepository runMetricSnapshotRepository;
    private final ComputationResolver computationResolver;
    private final EvalSummaryRepository evalSummaryRepository;
    private final EvalSummaryExportColumnPlanner columnPlanner;
    private final EvalSummaryExportColumnSelector columnSelector;
    private final FilterParser filterParser;
    private final ObjectMapper objectMapper;
    private final CsvExportProperties csvExportProperties;
    private final CsvDelimiterParser csvDelimiterParser;
    private final TransactionTemplate metaTransactionTemplate;
    private final TransactionTemplate analyticsTransactionTemplate;

    public EvalSummaryExportService(
            TestSuiteRunRepository runRepository,
            RunMetricSnapshotRepository runMetricSnapshotRepository,
            ComputationResolver computationResolver,
            EvalSummaryRepository evalSummaryRepository,
            EvalSummaryExportColumnPlanner columnPlanner,
            EvalSummaryExportColumnSelector columnSelector,
            FilterParser filterParser,
            ObjectMapper objectMapper,
            CsvExportProperties csvExportProperties,
            CsvDelimiterParser csvDelimiterParser,
            @Qualifier("metaTransactionManager") PlatformTransactionManager metaTxManager,
            @Qualifier("analyticsTransactionManager") PlatformTransactionManager analyticsTxManager) {
        this.runRepository = runRepository;
        this.runMetricSnapshotRepository = runMetricSnapshotRepository;
        this.computationResolver = computationResolver;
        this.evalSummaryRepository = evalSummaryRepository;
        this.columnPlanner = columnPlanner;
        this.columnSelector = columnSelector;
        this.filterParser = filterParser;
        this.objectMapper = objectMapper;
        this.csvExportProperties = csvExportProperties;
        this.csvDelimiterParser = csvDelimiterParser;
        this.metaTransactionTemplate = new TransactionTemplate(metaTxManager);
        this.metaTransactionTemplate.setReadOnly(true);
        this.analyticsTransactionTemplate = new TransactionTemplate(analyticsTxManager);
        this.analyticsTransactionTemplate.setReadOnly(true);
    }

    public void exportToCsv(EvalSummaryExportRequestDto request, HttpServletResponse response) {
        ExportContext context = resolveContext(request.getRunId(), request.getComputation());

        List<ColumnDescriptor> effective = columnSelector.select(context.fullManifest(), request.getColumns());
        boolean useJoinProjection = effective.stream().anyMatch(ColumnDescriptor::requiresJoinProjection);

        List<FilterCondition> filters = buildFiltersWithRunIdInjection(request.getFilter(), request.getRunId());
        char delimiter = csvDelimiterParser.parse(request.getDelimiter());

        int pageSize = csvExportProperties.getPageSize();
        // Fetch the first page BEFORE setting response headers so that filter / repository
        // validation errors translate to a clean 4xx via the exception handler chain instead
        // of being lost inside an already-committed 200 stream.
        CursorPage<EvalSummary> firstPage = fetchPage(
                filters, context.computationId(), context.runCreatedAtMs(), null, pageSize, useJoinProjection);

        String computationLabel = request.getComputation() == null ? LATEST_SENTINEL : request.getComputation();
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"eval-summary-" + request.getRunId() + "-" + computationLabel + ".csv\"");

        OutputStream out;
        try {
            out = response.getOutputStream();
        } catch (IOException e) {
            log.warn(
                    "Failed to obtain response output stream for export of run {}: {}",
                    request.getRunId(),
                    e.getMessage(),
                    e);
            throw new IllegalStateException("Failed to obtain response output stream for run " + request.getRunId(), e);
        }

        try (CSVPrinter printer = new CSVPrinter(
                new OutputStreamWriter(out, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setDelimiter(delimiter).get())) {

            List<String> headers = new ArrayList<>(effective.size());
            for (ColumnDescriptor descriptor : effective) {
                headers.add(descriptor.name());
            }
            printer.printRecord(headers);
            printer.flush();

            CursorPage<EvalSummary> page = firstPage;
            while (true) {
                for (EvalSummary summary : page.content()) {
                    EvalSummaryExportRow row = new EvalSummaryExportRow(summary, objectMapper);
                    List<String> cells = new ArrayList<>(effective.size());
                    for (ColumnDescriptor descriptor : effective) {
                        cells.add(formatCsvCell(descriptor.valueExtractor().apply(row)));
                    }
                    printer.printRecord(cells);
                }
                printer.flush();

                if (!page.hasMore()) {
                    break;
                }
                page = fetchPage(
                        filters,
                        context.computationId(),
                        context.runCreatedAtMs(),
                        page.nextCursor(),
                        pageSize,
                        useJoinProjection);
            }
        } catch (IOException e) {
            log.error("Failed to write CSV export for run {}: {}", request.getRunId(), e.getMessage(), e);
            throw new IllegalStateException("Failed to write CSV export for run " + request.getRunId(), e);
        }
    }

    public List<List<Object>> previewAsJson(UUID runId, String computation, List<String> filter) {
        ExportContext context = resolveContext(runId, computation);
        List<ColumnDescriptor> fullManifest = context.fullManifest();

        List<FilterCondition> filters = buildFiltersWithRunIdInjection(filter, runId);
        CursorPage<EvalSummary> page =
                fetchPage(filters, context.computationId(), context.runCreatedAtMs(), null, PREVIEW_PAGE_SIZE, true);

        List<List<Object>> result = new ArrayList<>(page.content().size() + 1);
        List<Object> headers = new ArrayList<>(fullManifest.size());
        for (ColumnDescriptor descriptor : fullManifest) {
            headers.add(descriptor.name());
        }
        result.add(headers);

        for (EvalSummary summary : page.content()) {
            EvalSummaryExportRow row = new EvalSummaryExportRow(summary, objectMapper);
            List<Object> cells = new ArrayList<>(fullManifest.size());
            for (ColumnDescriptor descriptor : fullManifest) {
                cells.add(descriptor.valueExtractor().apply(row));
            }
            result.add(cells);
        }
        return result;
    }

    private ExportContext resolveContext(UUID runId, String computation) {
        MetaSetup metaSetup = Objects.requireNonNull(metaTransactionTemplate.execute(status -> {
            TestSuiteRun run = runRepository
                    .findById(runId)
                    .orElseThrow(() -> new EntityNotFoundException("Test suite run not found: " + runId));
            if (!RunStatus.isTerminal(run.getStatus())) {
                throw new RunNotTerminalException("Run " + runId + " is not in a terminal state (current: "
                        + run.getStatus() + "); exports are only permitted for COMPLETED/FAILED/CANCELLED runs");
            }
            SuiteSnapshotDto snapshot = resolveSnapshot(run);
            return new MetaSetup(run, snapshot);
        }));

        return analyticsTransactionTemplate.execute(status -> {
            UUID computationId = computationResolver
                    .resolve(computation, metaSetup.run().getId())
                    .orElseThrow(() -> new EntityNotFoundException("No computation snapshot found for run "
                            + metaSetup.run().getId()
                            + " (computation="
                            + (computation == null ? LATEST_SENTINEL : computation) + ")"));

            List<RunMetricSnapshot> metricSnapshots =
                    runMetricSnapshotRepository.findByRunId(metaSetup.run().getId()).stream()
                            .filter(s -> computationId.equals(s.getComputationId()))
                            .toList();

            boolean explicitComputationUuid = computation != null && !LATEST_SENTINEL.equalsIgnoreCase(computation);
            if (explicitComputationUuid && metricSnapshots.isEmpty()) {
                throw new EntityNotFoundException("No computation snapshot found for run "
                        + metaSetup.run().getId() + " (computation=" + computation + ")");
            }

            List<ColumnDescriptor> fullManifest = columnPlanner.plan(metaSetup.snapshot(), metricSnapshots);
            if (fullManifest.size() > ValidationConstants.MAX_EXPORT_COLUMNS) {
                throw new ValidationException("Planner-derived export manifest has " + fullManifest.size()
                        + " columns, exceeding the cap of "
                        + ValidationConstants.MAX_EXPORT_COLUMNS);
            }

            return new ExportContext(computationId, metaSetup.run().getCreatedAt(), fullManifest);
        });
    }

    private SuiteSnapshotDto resolveSnapshot(TestSuiteRun run) {
        String snapshotJson = run.getSuiteSnapshot();
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new SnapshotSuiteMissingException(
                    "Run " + run.getId() + " has no suite_snapshot; legacy runs are not exportable");
        }
        SuiteSnapshotDto snapshot;
        try {
            snapshot = objectMapper.readValue(snapshotJson, SuiteSnapshotDto.class);
        } catch (JacksonException e) {
            log.error("Failed to deserialize suite_snapshot for run {}: {}", run.getId(), e.getMessage(), e);
            throw new IllegalStateException("Failed to deserialize suite_snapshot for run " + run.getId(), e);
        }
        String version = snapshot.getSnapshotVersion() != null
                ? snapshot.getSnapshotVersion()
                : SuiteSnapshotDto.CURRENT_VERSION;
        if (!SuiteSnapshotDto.CURRENT_VERSION.equals(version)) {
            throw new UnsupportedSnapshotVersionException("Unsupported snapshot version: " + version);
        }
        return snapshot;
    }

    private List<FilterCondition> buildFiltersWithRunIdInjection(List<String> rawFilters, UUID runId) {
        List<String> combined = new ArrayList<>();
        combined.add(RUN_ID_FILTER_FIELD + ":eq:" + runId);
        if (rawFilters != null) {
            combined.addAll(rawFilters);
        }
        return filterParser.parse(combined);
    }

    private CursorPage<EvalSummary> fetchPage(
            List<FilterCondition> filters,
            UUID computationId,
            Long runCreatedAtMs,
            Cursor cursor,
            int pageSize,
            boolean useJoinProjection) {
        return analyticsTransactionTemplate.execute(status -> {
            try {
                if (useJoinProjection) {
                    return evalSummaryRepository.findAllForExportWithBodies(
                            filters, computationId, runCreatedAtMs, cursor, pageSize);
                }
                return evalSummaryRepository.findAllForExport(filters, computationId, runCreatedAtMs, cursor, pageSize);
            } catch (InvalidFilterException ex) {
                throw new FilterValidationException(ex.getMessage(), ex.getDetails());
            }
        });
    }

    private String formatCsvCell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof JsonNode node) {
            if (node.isNull()) {
                return "";
            }
            if (node.isObject() || node.isArray()) {
                return node.toString();
            }
            return node.asText();
        }
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JacksonException e) {
                log.warn(
                        "Failed to serialize export cell value of type {}: {}",
                        value.getClass().getName(),
                        e.getMessage(),
                        e);
                return "";
            }
        }
        return String.valueOf(value);
    }

    private record MetaSetup(TestSuiteRun run, SuiteSnapshotDto snapshot) {}

    private record ExportContext(UUID computationId, Long runCreatedAtMs, List<ColumnDescriptor> fullManifest) {}
}
