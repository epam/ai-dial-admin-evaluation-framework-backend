package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

import com.epam.aidial.evaluation.configuration.properties.testsuite.EvaluationRunProperties;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.MultipartFormDataRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationResult;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;

/**
 * Multi-turn suite validation: a multi-turn suite uses its single {@code inputBindings} (validated by the
 * shared {@link BindingValidator}); the only multi-turn-specific config requirement is a chat-completions
 * body (top-level {@code messages} array). Turn count / array-shape are per-test-case runtime concerns.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SuiteValidationService multi-turn validation")
class SuiteValidationServiceMultiTurnTest {

    @Mock
    private EvaluationRunProperties evaluationRunProperties;

    @Mock
    private EvaluationRunProperties.Execution execution;

    @Mock
    private FileRefValidator fileRefValidator;

    @Mock
    private JsonbMapper jsonbMapper;

    private SuiteValidationService service;

    private static final List<FieldDefinitionDto> SCHEMA = List.of(
            FieldDefinitionDto.builder().name("q1").type(SchemaFieldType.STRING).build(),
            FieldDefinitionDto.builder().name("q2").type(SchemaFieldType.STRING).build());

    @BeforeEach
    void setUp() {
        TemplateVariableExtractor templateVariableExtractor = new TemplateVariableExtractor();
        BindingValidator bindingValidator = new BindingValidator(fileRefValidator);
        service = new SuiteValidationService(
                templateVariableExtractor, evaluationRunProperties, fileRefValidator, bindingValidator, jsonbMapper);
        lenient().when(evaluationRunProperties.getExecution()).thenReturn(execution);
        lenient().when(execution.getHeaderBlacklist()).thenReturn(List.of());
    }

    @Test
    @DisplayName("a well-formed multi-turn suite (single bindings + messages body) is valid")
    void wellFormedMultiTurnSuiteIsValid() {
        TestSuiteRequestDto dto = baseMultiTurnSuite(List.of(binding("q1")));

        ValidationResult result = service.validateSuite(dto, null, SCHEMA);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("non-messages JSON body is invalid")
    void nonMessagesJsonBodyIsInvalid() {
        TestSuiteRequestDto dto = baseMultiTurnSuite(List.of(binding("q1")));
        dto.setRequestTemplate(RequestTemplateDto.builder()
                .urlTemplate("/chat")
                .body(JsonRequestBodyDto.builder()
                        .content(Map.of("prompt", "${{turn}}"))
                        .build())
                .build());

        ValidationResult result = service.validateSuite(dto, null, SCHEMA);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings()).anyMatch(w -> w.getMessage().contains("top-level 'messages' array"));
    }

    @Test
    @DisplayName("multipart body is invalid for multi-turn")
    void multipartBodyIsInvalid() {
        TestSuiteRequestDto dto = baseMultiTurnSuite(List.of(binding("q1")));
        dto.setRequestTemplate(RequestTemplateDto.builder()
                .urlTemplate("/chat")
                .body(MultipartFormDataRequestBodyDto.builder()
                        .content(List.of())
                        .build())
                .build());

        ValidationResult result = service.validateSuite(dto, null, SCHEMA);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings()).anyMatch(w -> w.getMessage().contains("top-level 'messages' array"));
    }

    @Test
    @DisplayName("binding to an unknown field is invalid (shared binding validation)")
    void bindingToUnknownFieldIsInvalid() {
        TestSuiteRequestDto dto = baseMultiTurnSuite(List.of(binding("unknown_field")));

        ValidationResult result = service.validateSuite(dto, null, SCHEMA);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings()).anyMatch(w -> w.getMessage().contains("unknown field 'unknown_field'"));
    }

    private TestSuiteRequestDto baseMultiTurnSuite(List<InputBindingDto> inputBindings) {
        return TestSuiteRequestDto.builder()
                .name("multi-turn suite")
                .multiTurn(true)
                .inputBindings(inputBindings)
                .endpointRef(
                        EndpointContractDto.builder().method(HttpMethod.POST).build())
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/chat")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of(
                                        "model",
                                        "gpt-4",
                                        "messages",
                                        List.of(Map.of("role", "user", "content", "${{turn}}"))))
                                .build())
                        .build())
                .build();
    }

    private InputBindingDto binding(String dataField) {
        return InputBindingDto.builder()
                .templateVariable("turn")
                .dataField(dataField)
                .build();
    }
}
