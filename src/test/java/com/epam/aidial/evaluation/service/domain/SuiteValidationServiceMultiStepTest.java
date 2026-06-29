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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;

@ExtendWith(MockitoExtension.class)
@DisplayName("SuiteValidationService multi-step validation")
class SuiteValidationServiceMultiStepTest {

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
    @DisplayName("a well-formed multi-step suite is valid")
    void wellFormedMultiStepSuiteIsValid() {
        TestSuiteRequestDto dto = baseMultiStepSuite(List.of(step("q1"), step("q2")));

        ValidationResult result = service.validateSuite(dto, null, SCHEMA);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("empty multistepInputBindings is invalid")
    void emptyBindingsIsInvalid() {
        TestSuiteRequestDto dto = baseMultiStepSuite(List.of());

        ValidationResult result = service.validateSuite(dto, null, SCHEMA);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings())
                .anyMatch(w -> w.getMessage().contains("multistepInputBindings must be non-empty"));
    }

    @Test
    @DisplayName("over-cap multistepInputBindings is invalid")
    void overCapIsInvalid() {
        List<List<InputBindingDto>> steps =
                new ArrayList<>(IntStream.range(0, 11).mapToObj(i -> step("q1")).toList());
        TestSuiteRequestDto dto = baseMultiStepSuite(steps);

        ValidationResult result = service.validateSuite(dto, null, SCHEMA);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings()).anyMatch(w -> w.getMessage().contains("must not exceed 10 steps"));
    }

    @Test
    @DisplayName("non-messages JSON body is invalid")
    void nonMessagesJsonBodyIsInvalid() {
        TestSuiteRequestDto dto = baseMultiStepSuite(List.of(step("q1")));
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
    @DisplayName("multipart body is invalid for multi-step")
    void multipartBodyIsInvalid() {
        TestSuiteRequestDto dto = baseMultiStepSuite(List.of(step("q1")));
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
    @DisplayName("bad per-step binding (unknown field) is invalid")
    void badPerStepBindingIsInvalid() {
        TestSuiteRequestDto dto = baseMultiStepSuite(List.of(step("q1"), step("unknown_field")));

        ValidationResult result = service.validateSuite(dto, null, SCHEMA);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getWarnings())
                .anyMatch(w ->
                        w.getMessage().contains("Step 1") && w.getMessage().contains("unknown field 'unknown_field'"));
    }

    private TestSuiteRequestDto baseMultiStepSuite(List<List<InputBindingDto>> steps) {
        return TestSuiteRequestDto.builder()
                .name("multi-step suite")
                .multiStep(true)
                .multistepInputBindings(steps)
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

    private List<InputBindingDto> step(String dataField) {
        return List.of(InputBindingDto.builder()
                .templateVariable("turn")
                .dataField(dataField)
                .build());
    }
}
