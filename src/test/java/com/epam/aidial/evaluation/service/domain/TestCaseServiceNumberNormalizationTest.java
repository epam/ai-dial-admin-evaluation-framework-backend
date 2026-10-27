package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.util.TestCaseTurnsCsvSerializer;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.mapper.TestCaseMapper;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("TestCaseService NUMBER normalization")
class TestCaseServiceNumberNormalizationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private DatasetSchemaProvider datasetSchemaProvider;

    @Mock
    private TestCaseValidationService testCaseValidationService;

    @Mock
    private MultiTurnFieldsValidator multiTurnFieldsValidator;

    @Spy
    private ValidationWarningsSerializer warningsSerializer = new ValidationWarningsSerializer(objectMapper);

    @Spy
    private TestCaseTurnsCsvSerializer testCaseTurnsSerializer = new TestCaseTurnsCsvSerializer(objectMapper);

    @Spy
    private TestCaseMapper testCaseMapper = new TestCaseMapper(warningsSerializer, testCaseTurnsSerializer);

    @InjectMocks
    private TestCaseService service;

    @Test
    @DisplayName("Normalizes shared whole NUMBER values to decimal JSON before persistence")
    void normalizesSharedNumbers() {
        final TestCase saved = create(List.of(numberField("numeric", false)), Map.of("numeric", 1), null);

        assertThat(objectMapper.readTree(saved.getData()).get("numeric").toString())
                .isEqualTo("1.0");
        assertThat(saved.getMultiTurnData()).isNull();
    }

    @Test
    @DisplayName("Normalizes shared and per-turn NUMBER values in every turn without changing order or null turns")
    void normalizesMultiTurnNumbers() {
        final TestCase saved = create(
                List.of(numberField("shared", false), numberField("numeric", true)),
                Map.of("shared", 1),
                Arrays.asList(Map.of("numeric", 2), null, Map.of("numeric", 3)));
        final JsonNode turns = objectMapper.readTree(saved.getMultiTurnData());

        assertThat(objectMapper.readTree(saved.getData()).get("shared").toString())
                .isEqualTo("1.0");
        assertThat(turns.size()).isEqualTo(3);
        assertThat(turns.get(0).get("numeric").toString()).isEqualTo("2.0");
        assertThat(turns.get(1).isNull()).isTrue();
        assertThat(turns.get(2).get("numeric").toString()).isEqualTo("3.0");
    }

    @Test
    @DisplayName("Preserves shared and per-turn INTEGER values while normalizing schema-declared NUMBER fields")
    void preservesSchemaDeclaredIntegersDuringNumberNormalization() {
        final TestCase saved = create(
                List.of(
                        numberField("sharedNumber", false),
                        numberField("turnNumber", true),
                        FieldDefinitionDto.builder()
                                .name("sharedInteger")
                                .type(SchemaFieldType.INTEGER)
                                .perTurn(false)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("turnInteger")
                                .type(SchemaFieldType.INTEGER)
                                .perTurn(true)
                                .build()),
                Map.of("sharedNumber", 1, "sharedInteger", 1),
                List.of(Map.of("turnNumber", 2, "turnInteger", 2), Map.of("turnNumber", 3, "turnInteger", 3)));
        final JsonNode data = objectMapper.readTree(saved.getData());
        final JsonNode turns = objectMapper.readTree(saved.getMultiTurnData());

        assertThat(data.get("sharedNumber").toString()).isEqualTo("1.0");
        assertThat(data.get("sharedInteger").toString()).isEqualTo("1");
        assertThat(turns.get(0).get("turnNumber").toString()).isEqualTo("2.0");
        assertThat(turns.get(0).get("turnInteger").toString()).isEqualTo("2");
        assertThat(turns.get(1).get("turnNumber").toString()).isEqualTo("3.0");
        assertThat(turns.get(1).get("turnInteger").toString()).isEqualTo("3");
    }

    @Test
    @DisplayName("Leaves fractional NUMBER, INTEGER, unknown and nested numeric values unchanged")
    void leavesOtherNumbersUnchanged() {
        final TestCase saved = create(
                List.of(
                        numberField("numeric", false),
                        FieldDefinitionDto.builder()
                                .name("integer")
                                .type(SchemaFieldType.INTEGER)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("object")
                                .type(SchemaFieldType.OBJECT)
                                .build()),
                Map.of("numeric", 1.25, "integer", 1, "unknown", 1, "object", Map.of("numeric", 1)),
                null);
        final JsonNode data = objectMapper.readTree(saved.getData());

        assertThat(data.get("numeric").toString()).isEqualTo("1.25");
        assertThat(data.get("integer").toString()).isEqualTo("1");
        assertThat(data.get("unknown").toString()).isEqualTo("1");
        assertThat(data.get("object").get("numeric").toString()).isEqualTo("1");
    }

    @Test
    @DisplayName("Leaves strings, booleans, null and missing NUMBER values unchanged for validation")
    void doesNotCoerceNonNumericValues() {
        final Map<String, Object> values = new LinkedHashMap<>();
        values.put("text", "1");
        values.put("boolean", true);
        values.put("null", null);
        final TestCase saved = create(
                List.of(
                        numberField("text", false),
                        numberField("boolean", false),
                        numberField("null", false),
                        numberField("missing", false)),
                values,
                null);
        final JsonNode data = objectMapper.readTree(saved.getData());

        assertThat(data.get("text").toString()).isEqualTo("\"1\"");
        assertThat(data.get("boolean").toString()).isEqualTo("true");
        assertThat(data.get("null").isNull()).isTrue();
        assertThat(data.has("missing")).isFalse();
    }

    @Test
    @DisplayName("Adds .0 to large integral NUMBER values without losing precision")
    void preservesLargeIntegerPrecision() {
        final TestCase saved = create(List.of(numberField("numeric", false)), Map.of("numeric", Long.MAX_VALUE), null);

        assertThat(saved.getData()).isEqualTo("{\"numeric\":9223372036854775807.0}");
    }

    private FieldDefinitionDto numberField(String name, boolean perTurn) {
        return FieldDefinitionDto.builder()
                .name(name)
                .type(SchemaFieldType.NUMBER)
                .perTurn(perTurn)
                .build();
    }

    private TestCase create(
            List<FieldDefinitionDto> schema, Map<String, Object> data, List<Map<String, Object>> turns) {
        final UUID datasetId = UUID.randomUUID();
        final ValidationResult validation =
                ValidationResult.builder().valid(true).warnings(List.of()).build();
        when(datasetSchemaProvider.getSchema(datasetId)).thenReturn(schema);
        when(testCaseRepository.save(any(TestCase.class))).thenAnswer(invocation -> invocation.getArgument(0));
        if (turns == null) {
            when(testCaseValidationService.validateTestCase(
                            anyMap(), eq(schema), isNull(), anyList(), eq(false), eq(datasetId)))
                    .thenReturn(validation);
        } else {
            when(testCaseValidationService.validateMultiTurn(
                            anyMap(), anyList(), eq(schema), isNull(), anyList(), eq(false), eq(datasetId)))
                    .thenReturn(validation);
        }

        service.create(
                datasetId,
                TestCaseRequestDto.builder()
                        .testCaseName("NUMBER normalization")
                        .data(data)
                        .multiTurnData(turns)
                        .build(),
                false);

        final ArgumentCaptor<TestCase> saved = ArgumentCaptor.forClass(TestCase.class);
        verify(testCaseRepository).save(saved.capture());
        return saved.getValue();
    }
}
