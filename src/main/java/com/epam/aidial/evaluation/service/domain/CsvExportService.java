package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.properties.csv.CsvExportProperties;
import com.epam.aidial.evaluation.configuration.properties.pagination.PaginationProperties;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class CsvExportService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> TURNS_TYPE = new TypeReference<>() {};

    private final DatasetRepository datasetRepository;
    private final DatasetSchemaProvider datasetSchemaProvider;
    private final TestCaseRepository testCaseRepository;
    private final FilterParser filterParser;
    private final ObjectMapper objectMapper;
    private final CsvExportProperties csvExportProperties;
    private final PaginationProperties paginationProperties;

    /**
     * Exports test cases as CSV using paginated DB queries and writes directly to the output stream.
     * Schema is sourced from the dataset; the {@code enabled} column is no longer part of the export.
     */
    public void exportCsv(UUID datasetId, List<String> filter, char delimiter, OutputStream out) throws IOException {
        if (!datasetRepository.existsById(datasetId)) {
            throw new EntityNotFoundException("Dataset not found: " + datasetId);
        }

        List<FieldDefinitionDto> fields = datasetSchemaProvider.getSchema(datasetId);
        List<String> dataColumnNames = fields.stream()
                .map(FieldDefinitionDto::getName)
                .filter(name -> name != null && !name.isBlank())
                .toList();

        List<String> header = new ArrayList<>();
        header.add("testCaseName");
        header.add("turnIndex");
        header.addAll(dataColumnNames);

        List<FilterCondition> filters = filterParser.parse(filter != null ? filter : List.of());
        int pageSize = Math.clamp(csvExportProperties.getPageSize(), 1, paginationProperties.getMaxSize());

        CSVFormat format = CSVFormat.DEFAULT
                .builder()
                .setDelimiter(delimiter)
                .setRecordSeparator("\n")
                .get();

        try (OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
                CSVPrinter printer = new CSVPrinter(writer, format)) {
            int page = 0;
            boolean headerWritten = false;
            while (true) {
                PageRequest pageRequest = PageRequest.of(page, pageSize);
                Page<TestCase> pageResult =
                        testCaseRepository.findAllByDatasetId(datasetId, pageRequest, filters, false);
                List<TestCase> cases = pageResult.getContent();
                if (cases.isEmpty()) {
                    break;
                }
                if (!headerWritten) {
                    printer.printRecord(header);
                    headerWritten = true;
                }
                for (TestCase tc : cases) {
                    String name = tc.getTestCaseName() != null ? tc.getTestCaseName() : "";
                    if (tc.getMultiTurnData() != null) {
                        // Multi-turn cases are multiplied to one flat row per turn, sharing testCaseName,
                        // with turnIndex 0..N-1 in order. The case's shared (test-case-level) data is merged
                        // into every turn row, so shared columns are repeated identically across the rows.
                        Map<String, Object> sharedData = parseJsonToMap(tc.getData());
                        List<Map<String, Object>> turns = parseTurns(tc.getMultiTurnData());
                        for (int i = 0; i < turns.size(); i++) {
                            Map<String, Object> row = new LinkedHashMap<>(sharedData);
                            row.putAll(turns.get(i));
                            printer.printRecord(buildRow(name, String.valueOf(i), row, dataColumnNames));
                        }
                    } else {
                        // Single-turn case → one row with a blank turnIndex.
                        printer.printRecord(buildRow(name, "", parseJsonToMap(tc.getData()), dataColumnNames));
                    }
                }
                if (cases.size() < pageSize) {
                    break;
                }
                page++;
            }
            if (!headerWritten) {
                printer.printRecord(header);
            }
        }
    }

    private List<Object> buildRow(
            String testCaseName, String turnIndex, Map<String, Object> data, List<String> dataColumnNames) {
        List<Object> row = new ArrayList<>();
        row.add(testCaseName);
        row.add(turnIndex);
        for (String name : dataColumnNames) {
            row.add(cellValue(data.get(name)));
        }
        return row;
    }

    private List<Map<String, Object>> parseTurns(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> turns = objectMapper.readValue(json, TURNS_TYPE);
            return turns != null ? turns : List.of();
        } catch (JacksonException e) {
            log.warn("Failed to parse multiTurnData for export, treating as no turns: {}", e.getMessage(), e);
            return List.of();
        }
    }

    private String cellValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List || value instanceof Map) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JacksonException e) {
                throw new IllegalStateException("Failed to serialize cell value to JSON", e);
            }
        }
        return value.toString();
    }

    private Map<String, Object> parseJsonToMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
