package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.configuration.properties.csv.CsvExportProperties;
import com.epam.aidial.evaluation.configuration.properties.pagination.PaginationProperties;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.DatasetRepository;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Exports test cases as a ZIP archive containing test-cases.csv and a files/ directory
 * when FILE-type fields are present and materializeFiles is true.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class ZipExportService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final DatasetRepository datasetRepository;
    private final DatasetSchemaProvider datasetSchemaProvider;
    private final TestCaseRepository testCaseRepository;
    private final DialFileClient dialFileClient;
    private final DialFileRefResolver dialFileRefResolver;
    private final FileRefValidator fileRefValidator;
    private final FilterParser filterParser;
    private final ObjectMapper objectMapper;
    private final CsvExportProperties csvExportProperties;
    private final PaginationProperties paginationProperties;

    /**
     * Returns true if the dataset's schema contains at least one FILE-type field.
     */
    public boolean hasFileFields(UUID datasetId) {
        if (!datasetRepository.existsById(datasetId)) {
            throw new EntityNotFoundException("Dataset not found: " + datasetId);
        }
        List<FieldDefinitionDto> fields = datasetSchemaProvider.getSchema(datasetId);
        return fields.stream().anyMatch(f -> f != null && f.getType() == SchemaFieldType.FILE);
    }

    /**
     * Exports test cases as ZIP with CSV + files. Streams to output without full in-memory buffering.
     * FILE field values (DIAL file references) are replaced with relative paths in the CSV,
     * and actual file bytes are downloaded from DIAL and embedded in the ZIP.
     */
    public void exportZip(UUID datasetId, List<String> filter, char delimiter, OutputStream out) throws IOException {
        if (!datasetRepository.existsById(datasetId)) {
            throw new EntityNotFoundException("Dataset not found: " + datasetId);
        }

        List<FieldDefinitionDto> fields = datasetSchemaProvider.getSchema(datasetId);
        List<String> dataColumnNames = fields.stream()
                .map(FieldDefinitionDto::getName)
                .filter(name -> name != null && !name.isBlank())
                .toList();

        Set<String> fileFieldNames = fields.stream()
                .filter(f -> f != null && f.getType() == SchemaFieldType.FILE)
                .map(FieldDefinitionDto::getName)
                .collect(Collectors.toSet());

        List<FilterCondition> filters = filterParser.parse(filter != null ? filter : List.of());
        int pageSize = Math.min(Math.max(1, csvExportProperties.getPageSize()), paginationProperties.getMaxSize());

        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            List<CsvRow> allRows = collectRows(datasetId, dataColumnNames, fileFieldNames, pageSize, filters);

            // Write CSV entry
            zos.putNextEntry(new ZipEntry("test-cases.csv"));
            writeCsvToZip(zos, dataColumnNames, delimiter, allRows);
            zos.closeEntry();

            // Write file entries
            for (CsvRow row : allRows) {
                if (row.fileEntries == null) {
                    continue;
                }
                for (FileEntry entry : row.fileEntries) {
                    try {
                        String zipPath = "files/" + row.rowIndex + "/" + entry.fieldName + "/" + entry.filename;
                        zos.putNextEntry(new ZipEntry(zipPath));
                        String realPath = dialFileRefResolver.resolveToRealPath(entry.dialRef);
                        dialFileClient.downloadTo(realPath, zos);
                        zos.closeEntry();
                    } catch (Exception e) {
                        log.warn(
                                "Failed to download file for ZIP export: ref={}, error={}",
                                entry.dialRef,
                                e.getMessage(),
                                e);
                    }
                }
            }
        }
    }

    private List<CsvRow> collectRows(
            UUID datasetId,
            List<String> dataColumnNames,
            Set<String> fileFieldNames,
            int pageSize,
            List<FilterCondition> filters) {
        List<CsvRow> rows = new ArrayList<>();
        int page = 0;
        int rowIndex = 1;

        while (true) {
            PageRequest pageRequest = PageRequest.of(page, pageSize);
            Page<TestCase> pageResult = testCaseRepository.findAllByDatasetId(datasetId, pageRequest, filters, false);
            List<TestCase> cases = pageResult.getContent();
            if (cases.isEmpty()) {
                break;
            }

            for (TestCase tc : cases) {
                Map<String, Object> data = parseJsonToMap(tc.getData());
                List<Object> values = new ArrayList<>();
                values.add(tc.getTestCaseName() != null ? tc.getTestCaseName() : "");

                List<FileEntry> fileEntries = new ArrayList<>();

                for (String name : dataColumnNames) {
                    Object value = data.get(name);
                    if (fileFieldNames.contains(name) && value != null) {
                        String dialRef = value.toString();
                        if (!dialRef.isBlank()
                                && fileRefValidator.validateFormat(dialRef).isEmpty()) {
                            String filename = dialFileRefResolver.extractFilename(dialRef);
                            String relativePath = "files/" + rowIndex + "/" + name + "/" + filename;
                            values.add(relativePath);
                            fileEntries.add(new FileEntry(name, dialRef, filename));
                        } else {
                            values.add(cellValue(value));
                        }
                    } else {
                        values.add(cellValue(value));
                    }
                }

                rows.add(new CsvRow(rowIndex, values, fileEntries.isEmpty() ? null : fileEntries));
                rowIndex++;
            }

            if (cases.size() < pageSize) {
                break;
            }
            page++;
        }
        return rows;
    }

    private void writeCsvToZip(ZipOutputStream zos, List<String> dataColumnNames, char delimiter, List<CsvRow> rows)
            throws IOException {
        List<String> header = new ArrayList<>();
        header.add("testCaseName");
        header.addAll(dataColumnNames);

        CSVFormat format = CSVFormat.DEFAULT
                .builder()
                .setDelimiter(delimiter)
                .setRecordSeparator("\n")
                .get();

        OutputStreamWriter writer = new OutputStreamWriter(zos, StandardCharsets.UTF_8);
        CSVPrinter printer = new CSVPrinter(writer, format);
        printer.printRecord(header);

        for (CsvRow row : rows) {
            printer.printRecord(row.values);
        }
        printer.flush();
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

    private record CsvRow(int rowIndex, List<Object> values, List<FileEntry> fileEntries) {}

    private record FileEntry(String fieldName, String dialRef, String filename) {}
}
