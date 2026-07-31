package com.epam.aidial.evaluation.service.domain.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.analytics.AnalyticsResultsProperties;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import com.epam.aidial.evaluation.service.domain.DatasetSchemaProvider;
import com.epam.aidial.evaluation.service.domain.SchemaValidationService;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.ObjectMapper;

@DisplayName("EvalResultsCsvParser")
@ExtendWith(MockitoExtension.class)
class EvalResultsCsvParserTest {

    @Mock
    private DatasetSchemaProvider datasetSchemaProvider;

    @Mock
    private SchemaValidationService schemaValidationService;

    private EvalResultsCsvParser parser;
    private UUID datasetId;

    @BeforeEach
    void setUp() {
        AnalyticsResultsProperties.CsvImport csvImport = new AnalyticsResultsProperties.CsvImport();
        csvImport.setMaxFileSize(DataSize.ofMegabytes(10));
        AnalyticsResultsProperties.Batch batch = new AnalyticsResultsProperties.Batch();
        batch.setMaxItems(10000);
        AnalyticsResultsProperties analyticsResultsProperties = new AnalyticsResultsProperties();
        analyticsResultsProperties.setCsvImport(csvImport);
        analyticsResultsProperties.setBatch(batch);

        parser = new EvalResultsCsvParser(
                datasetSchemaProvider, new ObjectMapper(), analyticsResultsProperties, schemaValidationService);

        datasetId = UUID.randomUUID();

        // By default, no schema. lenient() because some nested tests create their own parser instances.
        lenient().when(datasetSchemaProvider.getSchema(any())).thenReturn(List.of());
        // By default, schema validation passes.
        lenient()
                .when(schemaValidationService.validate(any(), any()))
                .thenReturn(ValidationResult.builder()
                        .valid(true)
                        .warnings(List.of())
                        .build());
    }

    private InputStream csvStream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    private long byteSize(String csv) {
        return csv.getBytes(StandardCharsets.UTF_8).length;
    }

    // -------------------------------------------------------------------------
    // Happy-path column binding
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("column binding")
    class ColumnBindingTests {

        @Test
        @DisplayName("reserved columns are bound to structural fields; testCaseData is parsed as a JSON object")
        void reservedColumnsIncludingTestCaseData() {
            // The JSON value contains commas and double-quotes → must be CSV-quoted (outer quotes + internal ""
            // escaping)
            String csv = "testCaseName,runIndex,executionStatus,startedAt,completedAt,testCaseData\n"
                    + "tc1,0,SUCCESS,1000,1500,"
                    + "\"{\"\"question\"\":\"\"What is the capital of France?\"\",\"\"expected\"\":\"\"Paris\"\"}\"\n";
            final List<TestCaseRunResult> items = parser.parse(datasetId, csvStream(csv), byteSize(csv), ',');

            assertThat(items).hasSize(1);
            final TestCaseRunResult item = items.get(0);
            assertThat(item.getTestCaseName()).isEqualTo("tc1");
            assertThat(item.getRunIndex()).isEqualTo(0);
            assertThat(item.getExecutionStatus()).isEqualTo(ExecutionStatus.SUCCESS);
            assertThat(item.getTestCaseData()).contains("\"question\"");
            assertThat(item.getTestCaseData()).contains("\"expected\"");
            assertThat(item.getTestCaseData()).contains("Paris");
        }

        @Test
        @DisplayName("testCaseId UUID is parsed correctly when present")
        void parsesTestCaseIdWhenPresent() {
            final UUID id = UUID.randomUUID();
            String csv = "testCaseId,testCaseName,runIndex,executionStatus,startedAt,completedAt,testCaseData\n" + id
                    + ",tc1,0,SUCCESS,1000,1500,{}\n";
            final List<TestCaseRunResult> items = parser.parse(datasetId, csvStream(csv), byteSize(csv), ',');

            assertThat(items).hasSize(1);
            assertThat(items.get(0).getTestCaseId()).isEqualTo(id);
        }

        @Test
        @DisplayName("unknown (non-reserved) columns are silently ignored")
        void unknownColumnsIgnored() {
            String csv = """
                    testCaseName,runIndex,executionStatus,startedAt,completedAt,testCaseData,someUnknownColumn
                    tc1,0,SUCCESS,1000,1500,{"q":"hello"},ignored_value
                    """;
            final List<TestCaseRunResult> items = parser.parse(datasetId, csvStream(csv), byteSize(csv), ',');

            assertThat(items).hasSize(1);
            assertThat(items.get(0).getTestCaseData()).contains("\"q\"");
            assertThat(items.get(0).getTestCaseData()).doesNotContain("someUnknownColumn");
            assertThat(items.get(0).getTestCaseData()).doesNotContain("ignored_value");
        }
    }

    // -------------------------------------------------------------------------
    // testCaseData parsing and validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("testCaseData parsing")
    class TestCaseDataParsingTests {

        @Test
        @DisplayName("missing testCaseData column is rejected")
        void missingTestCaseDataRejected() {
            String csv = """
                    testCaseName,runIndex,executionStatus,startedAt,completedAt
                    tc1,0,SUCCESS,1000,1500
                    """;
            assertThatThrownBy(() -> parser.parse(datasetId, csvStream(csv), byteSize(csv), ','))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("testCaseData");
        }

        @Test
        @DisplayName("blank testCaseData cell is rejected")
        void blankTestCaseDataRejected() {
            String csv = """
                    testCaseName,runIndex,executionStatus,startedAt,completedAt,testCaseData
                    tc1,0,SUCCESS,1000,1500,
                    """;
            assertThatThrownBy(() -> parser.parse(datasetId, csvStream(csv), byteSize(csv), ','))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("testCaseData");
        }

        @Test
        @DisplayName("non-object JSON in testCaseData (array) is rejected")
        void arrayTestCaseDataRejected() {
            String csv = """
                    testCaseName,runIndex,executionStatus,startedAt,completedAt,testCaseData
                    tc1,0,SUCCESS,1000,1500,[1,2,3]
                    """;
            assertThatThrownBy(() -> parser.parse(datasetId, csvStream(csv), byteSize(csv), ','))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("testCaseData")
                    .hasMessageContaining("row 0");
        }

        @Test
        @DisplayName("malformed JSON in testCaseData is rejected")
        void malformedTestCaseDataRejected() {
            String csv = """
                    testCaseName,runIndex,executionStatus,startedAt,completedAt,testCaseData
                    tc1,0,SUCCESS,1000,1500,not-json
                    """;
            assertThatThrownBy(() -> parser.parse(datasetId, csvStream(csv), byteSize(csv), ','))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("testCaseData")
                    .hasMessageContaining("row 0");
        }

        @Test
        @DisplayName("testCaseData violating the dataset schema is reported with its row number")
        void schemaViolationSurfacedWithRowNumber() {
            when(datasetSchemaProvider.getSchema(datasetId))
                    .thenReturn(List.of(FieldDefinitionDto.builder()
                            .name("question")
                            .type(SchemaFieldType.STRING)
                            .required(true)
                            .build()));
            when(schemaValidationService.validate(any(), any()))
                    .thenReturn(ValidationResult.builder()
                            .valid(false)
                            .warnings(List.of(ValidationWarningDto.builder()
                                    .path("$.question")
                                    .message("required property 'question' not found")
                                    .build()))
                            .build());

            String csv = """
                    testCaseName,runIndex,executionStatus,startedAt,completedAt,testCaseData
                    tc1,0,SUCCESS,1000,1500,{"wrong_field":"value"}
                    """;
            assertThatThrownBy(() -> parser.parse(datasetId, csvStream(csv), byteSize(csv), ','))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("testCaseData validation failed")
                    .hasMessageContaining("row 0");
        }
    }

    // -------------------------------------------------------------------------
    // JSON-blob column parsing
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("JSON column parsing")
    class JsonColumnParsingTests {

        @Test
        @DisplayName("requestBody, responseBody, and logDetails are parsed as JSON when non-blank")
        void parsesJsonColumnsCorrectly() {
            String csv = "testCaseName,runIndex,requestBody,responseBody,logDetails,"
                    + "executionStatus,startedAt,completedAt,testCaseData\n"
                    + "tc1,0,\"{\"\"q\"\":\"\"hello\"\"}\"," // requestBody JSON
                    + "\"{\"\"answer\"\":\"\"world\"\"}\"," // responseBody JSON
                    + "\"{\"\"level\"\":\"\"debug\"\"}\"," // logDetails JSON
                    + "SUCCESS,1000,1500,{}\n";
            final List<TestCaseRunResult> items = parser.parse(datasetId, csvStream(csv), byteSize(csv), ',');

            assertThat(items).hasSize(1);
            assertThat(items.get(0).getRequestBody()).contains("\"q\"");
            assertThat(items.get(0).getRequestBody()).contains("hello");
            assertThat(items.get(0).getResponseBody()).contains("\"answer\"");
            assertThat(items.get(0).getResponseBody()).contains("world");
        }

        @Test
        @DisplayName("blank requestBody / responseBody / logDetails cells map to null")
        void blankJsonColumnsMapToNull() {
            String csv = """
                    testCaseName,runIndex,requestBody,responseBody,logDetails,executionStatus,startedAt,completedAt,testCaseData
                    tc1,0,,,, SUCCESS,1000,1500,{}
                    """;
            final List<TestCaseRunResult> items = parser.parse(datasetId, csvStream(csv), byteSize(csv), ',');

            assertThat(items).hasSize(1);
            assertThat(items.get(0).getRequestBody()).isNull();
            assertThat(items.get(0).getResponseBody()).isNull();
        }
    }

    // -------------------------------------------------------------------------
    // Error scenarios (per-row)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("per-row validation errors")
    class PerRowValidationTests {

        @Test
        @DisplayName("a malformed JSON cell in responseBody is reported with its row number")
        void malformedJsonCellSurfacedWithRowNumber() {
            String csv = """
                    testCaseName,runIndex,responseBody,executionStatus,startedAt,completedAt,testCaseData
                    tc1,0,not-valid-json,SUCCESS,1000,1500,{}
                    """;
            assertThatThrownBy(() -> parser.parse(datasetId, csvStream(csv), byteSize(csv), ','))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("responseBody")
                    .hasMessageContaining("row 0");
        }

        @Test
        @DisplayName("an invalid executionStatus value is reported with its row number")
        void invalidExecutionStatusSurfacedWithRowNumber() {
            String csv = """
                    testCaseName,runIndex,executionStatus,startedAt,completedAt,testCaseData
                    tc1,0,NOT_A_STATUS,1000,1500,{}
                    """;
            assertThatThrownBy(() -> parser.parse(datasetId, csvStream(csv), byteSize(csv), ','))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("executionStatus")
                    .hasMessageContaining("row 0");
        }

        @Test
        @DisplayName("missing required reserved column (executionStatus) is surfaced via inline check")
        void missingRequiredReservedColumnSurfaced() {
            String csv = """
                    testCaseName,runIndex,startedAt,completedAt,testCaseData
                    tc1,0,1000,1500,{}
                    """;
            assertThatThrownBy(() -> parser.parse(datasetId, csvStream(csv), byteSize(csv), ','))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("violations from multiple independent rows are collected into one exception")
        void multipleRowViolationsCollectedTogether() {
            String csv = """
                    testCaseName,runIndex,executionStatus,startedAt,completedAt,testCaseData
                    tc1,0,BAD,1000,1500,{}
                    tc2,0,ALSO_BAD,1000,1500,{}
                    """;
            assertThatThrownBy(() -> parser.parse(datasetId, csvStream(csv), byteSize(csv), ','))
                    .isInstanceOf(ValidationException.class)
                    .hasMessageContaining("row 0")
                    .hasMessageContaining("row 1");
        }
    }

    // -------------------------------------------------------------------------
    // File-level rejections
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("file-level rejections")
    class FileLevelRejectionTests {

        @Test
        @DisplayName("file exceeding the configured max-file-size is rejected")
        void fileSizeExceededRejection() {
            AnalyticsResultsProperties.CsvImport csvImport = new AnalyticsResultsProperties.CsvImport();
            csvImport.setMaxFileSize(DataSize.ofBytes(10));
            AnalyticsResultsProperties.Batch batch = new AnalyticsResultsProperties.Batch();
            batch.setMaxItems(10000);
            AnalyticsResultsProperties smallSizeProps = new AnalyticsResultsProperties();
            smallSizeProps.setCsvImport(csvImport);
            smallSizeProps.setBatch(batch);

            EvalResultsCsvParser smallSizeParser = new EvalResultsCsvParser(
                    datasetSchemaProvider, new ObjectMapper(), smallSizeProps, schemaValidationService);

            String csv = "testCaseName,runIndex,executionStatus,startedAt,completedAt,testCaseData\n"
                    + "tc1,0,SUCCESS,1000,1500,{}\n";
            assertThatThrownBy(() -> smallSizeParser.parse(datasetId, csvStream(csv), byteSize(csv) + 1, ','))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("item count exceeding configured max-items is rejected mid-parse")
        void itemCountCapRejection() {
            AnalyticsResultsProperties.CsvImport csvImport = new AnalyticsResultsProperties.CsvImport();
            csvImport.setMaxFileSize(DataSize.ofMegabytes(10));
            AnalyticsResultsProperties.Batch batch = new AnalyticsResultsProperties.Batch();
            batch.setMaxItems(1);
            AnalyticsResultsProperties capProps = new AnalyticsResultsProperties();
            capProps.setCsvImport(csvImport);
            capProps.setBatch(batch);

            EvalResultsCsvParser capParser = new EvalResultsCsvParser(
                    datasetSchemaProvider, new ObjectMapper(), capProps, schemaValidationService);

            String csv = """
                    testCaseName,runIndex,executionStatus,startedAt,completedAt,testCaseData
                    tc1,0,SUCCESS,1000,1500,{}
                    tc2,0,SUCCESS,1000,1500,{}
                    """;
            assertThatThrownBy(() -> capParser.parse(datasetId, csvStream(csv), byteSize(csv), ','))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("empty CSV (header only, no data rows) is rejected")
        void emptyCsvRejected() {
            String csv = "testCaseName,runIndex,executionStatus,startedAt,completedAt,testCaseData\n";
            assertThatThrownBy(() -> parser.parse(datasetId, csvStream(csv), byteSize(csv), ','))
                    .isInstanceOf(ValidationException.class);
        }

        @Test
        @DisplayName("CSV with no header row at all is rejected")
        void noHeaderRowRejected() {
            String csv = "";
            assertThatThrownBy(() -> parser.parse(datasetId, csvStream(csv), byteSize(csv), ','))
                    .isInstanceOf(ValidationException.class);
        }
    }
}
