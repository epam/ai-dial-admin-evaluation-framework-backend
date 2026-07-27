package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateVariableResolverTest {

    private final TemplateVariableResolver resolver = new TemplateVariableResolver();

    @Test
    void shouldResolveConstantValueBinding() {
        InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("model")
                .constantValue("gpt-4")
                .build();
        List<ValidationWarningDto> warnings = new ArrayList<>();

        Object result = resolver.resolveVariable("model", null, binding, ResolutionScope.ofData(null), warnings);

        assertThat(result).isEqualTo("gpt-4");
        assertThat(warnings).isEmpty();
    }

    @Test
    void shouldResolveDataFieldBindingWithDataPresent() {
        InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("prompt")
                .dataField("promptField")
                .build();
        Map<String, Object> data = Map.of("promptField", "Hello world");
        List<ValidationWarningDto> warnings = new ArrayList<>();

        Object result = resolver.resolveVariable("prompt", null, binding, ResolutionScope.ofData(data), warnings);

        assertThat(result).isEqualTo("Hello world");
        assertThat(warnings).isEmpty();
    }

    @Test
    void shouldFallBackToDefaultWhenDataFieldMissing() {
        InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("prompt")
                .dataField("missingField")
                .build();
        Map<String, Object> data = Map.of();
        List<ValidationWarningDto> warnings = new ArrayList<>();

        Object result = resolver.resolveVariable("prompt", "fallback", binding, ResolutionScope.ofData(data), warnings);

        assertThat(result).isEqualTo("fallback");
        assertThat(warnings).isEmpty();
    }

    @Test
    void shouldResolveUnboundVariableWithDefault() {
        List<ValidationWarningDto> warnings = new ArrayList<>();

        Object result = resolver.resolveVariable("temperature", "0.7", null, ResolutionScope.ofData(null), warnings);

        assertThat(result).isEqualTo("0.7");
        assertThat(warnings).isEmpty();
    }

    @Test
    void shouldReturnNullAndWarnForUnboundVariableWithoutDefault() {
        List<ValidationWarningDto> warnings = new ArrayList<>();

        Object result = resolver.resolveVariable("prompt", null, null, ResolutionScope.ofData(null), warnings);

        assertThat(result).isNull();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).contains("prompt");
    }

    @Test
    void shouldReturnNullAndWarnForDataFieldBindingWithMissingDataAndNoDefault() {
        InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("prompt")
                .dataField("missingField")
                .build();
        Map<String, Object> data = Map.of();
        List<ValidationWarningDto> warnings = new ArrayList<>();

        Object result = resolver.resolveVariable("prompt", null, binding, ResolutionScope.ofData(data), warnings);

        assertThat(result).isNull();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0).getMessage()).contains("missingField");
    }

    @Test
    void shouldPreserveTypedValuesFromData() {
        InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("temperature")
                .dataField("temp")
                .build();
        Map<String, Object> data = Map.of("temp", 0.7);
        List<ValidationWarningDto> warnings = new ArrayList<>();

        Object result = resolver.resolveVariable("temperature", null, binding, ResolutionScope.ofData(data), warnings);

        assertThat(result).isEqualTo(0.7);
        assertThat(result).isInstanceOf(Double.class);
        assertThat(warnings).isEmpty();
    }

    @Test
    void shouldPreserveBooleanConstantValue() {
        InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("stream")
                .constantValue(true)
                .build();
        List<ValidationWarningDto> warnings = new ArrayList<>();

        Object result = resolver.resolveVariable("stream", null, binding, ResolutionScope.ofData(null), warnings);

        assertThat(result).isEqualTo(true);
        assertThat(result).isInstanceOf(Boolean.class);
        assertThat(warnings).isEmpty();
    }

    @Test
    void shouldConstantValueWinOverDataField() {
        // constantValue should always win even if dataField is also set
        InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("model")
                .constantValue("gpt-4")
                .dataField("modelField")
                .build();
        Map<String, Object> data = Map.of("modelField", "gpt-3.5");
        List<ValidationWarningDto> warnings = new ArrayList<>();

        Object result =
                resolver.resolveVariable("model", "default-model", binding, ResolutionScope.ofData(data), warnings);

        assertThat(result).isEqualTo("gpt-4");
        assertThat(warnings).isEmpty();
    }

    @Test
    void shouldTreatNullDataAsEmptyMap() {
        InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("prompt")
                .dataField("promptField")
                .build();
        List<ValidationWarningDto> warnings = new ArrayList<>();

        Object result = resolver.resolveVariable("prompt", "default", binding, ResolutionScope.ofData(null), warnings);

        assertThat(result).isEqualTo("default");
        assertThat(warnings).isEmpty();
    }
}
