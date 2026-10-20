package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.service.FilePlaceholderDetector;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TryItOutVariableBindingsTest {

    private final TryItOutVariableBindings bindings = new TryItOutVariableBindings(new FilePlaceholderDetector());

    @Test
    @DisplayName("Every index 0..requestCount-1 is present, in order")
    void shouldContainAllIndicesInOrder() {
        final Map<Integer, List<InputBindingDto>> result = bindings.toBindingsByRequest(Map.of(1, Map.of("a", "x")), 3);

        assertThat(result.keySet()).containsExactly(0, 1, 2);
        assertThat(result.get(1)).hasSize(1);
        assertThat(result.get(1).get(0).getTemplateVariable()).isEqualTo("a");
        assertThat(result.get(1).get(0).getConstantValue()).isEqualTo("x");
    }

    @Test
    @DisplayName("Absent and null inner maps yield empty lists")
    void shouldYieldEmptyListForAbsentAndNullEntries() {
        final Map<Integer, Map<String, Object>> variables = new HashMap<>();
        variables.put(1, null);

        final Map<Integer, List<InputBindingDto>> result = bindings.toBindingsByRequest(variables, 3);

        assertThat(result.get(0)).isEmpty();
        assertThat(result.get(1)).isEmpty();
        assertThat(result.get(2)).isEmpty();
    }

    @Test
    @DisplayName("Blank names and null values are skipped")
    void shouldSkipBlankNamesAndNullValues() {
        final Map<String, Object> inner = new LinkedHashMap<>();
        inner.put(" ", "v");
        inner.put("", "v");
        inner.put("nullValue", null);
        inner.put("kept", 1);

        final Map<Integer, List<InputBindingDto>> result = bindings.toBindingsByRequest(Map.of(0, inner), 1);

        assertThat(result.get(0)).hasSize(1);
        assertThat(result.get(0).get(0).getTemplateVariable()).isEqualTo("kept");
    }

    @Test
    @DisplayName("Index equal to requestCount is rejected with the exact message")
    void shouldRejectIndexEqualToRequestCount() {
        assertThatThrownBy(() -> bindings.toBindingsByRequest(Map.of(2, Map.of("a", "x")), 2))
                .isInstanceOf(ValidationException.class)
                .hasMessage("variables: request index 2 is out of range (chain length 2)");
    }

    @Test
    @DisplayName("Negative index is rejected with the exact message")
    void shouldRejectNegativeIndex() {
        assertThatThrownBy(() -> bindings.toBindingsByRequest(Map.of(-1, Map.of("a", "x")), 2))
                .isInstanceOf(ValidationException.class)
                .hasMessage("variables: request index -1 is out of range (chain length 2)");
    }

    @Test
    @DisplayName("Null index is rejected")
    void shouldRejectNullIndex() {
        final Map<Integer, Map<String, Object>> variables = new HashMap<>();
        variables.put(null, Map.of("a", "x"));

        assertThatThrownBy(() -> bindings.toBindingsByRequest(variables, 2))
                .isInstanceOf(ValidationException.class)
                .hasMessage("variables: request index must not be null");
    }

    @Test
    @DisplayName("Key 1 is rejected when requestCount is 1")
    void shouldRejectIndexOneForSingleRequest() {
        assertThatThrownBy(() -> bindings.toBindingsByRequest(Map.of(1, Map.of("a", "x")), 1))
                .isInstanceOf(ValidationException.class)
                .hasMessage("variables: request index 1 is out of range (chain length 1)");
    }

    private static InputBindingDto column(String variable, String dataField) {
        return InputBindingDto.builder()
                .templateVariable(variable)
                .dataField(dataField)
                .build();
    }

    private static InputBindingDto constant(String variable, Object value) {
        return InputBindingDto.builder()
                .templateVariable(variable)
                .constantValue(value)
                .build();
    }

    private static FieldDefinitionDto field(String name, SchemaFieldType type) {
        return FieldDefinitionDto.builder().name(name).type(type).build();
    }

    @Test
    @DisplayName("Variable bound to a FILE column in the suite becomes a dataField binding with the value as data")
    void shouldBindFileColumnVariableToDataField() {
        final TryItOutVariableBindings.VariableResolution result = bindings.bindToFileColumns(
                List.of(constant("doc", "@ef/suites/x/a.pdf")),
                List.of(column("doc", "attachment")),
                List.of(field("attachment", SchemaFieldType.FILE)));

        assertThat(result.bindings()).containsExactly(column("doc", "attachment"));
        assertThat(result.data()).containsEntry("attachment", "@ef/suites/x/a.pdf");
    }

    @Test
    @DisplayName("Variable bound to a non-FILE column, a constant or nothing stays a constant binding")
    void shouldKeepConstantBindingWhenNotFileColumn() {
        final List<InputBindingDto> constants =
                List.of(constant("title", "t"), constant("fixed", "f"), constant("free", "x"));

        final TryItOutVariableBindings.VariableResolution result = bindings.bindToFileColumns(
                constants,
                List.of(column("title", "title_col"), constant("fixed", "suiteConst")),
                List.of(field("title_col", SchemaFieldType.STRING)));

        assertThat(result.bindings()).isEqualTo(constants);
        assertThat(result.data()).isEmpty();
    }

    @Test
    @DisplayName("Second variable bound to the same FILE column stays a constant binding")
    void shouldKeepSecondVariableOnSharedColumnConstant() {
        final TryItOutVariableBindings.VariableResolution result = bindings.bindToFileColumns(
                List.of(constant("a", "first"), constant("b", "second")),
                List.of(column("a", "attachment"), column("b", "attachment")),
                List.of(field("attachment", SchemaFieldType.FILE)));

        assertThat(result.bindings()).containsExactly(column("a", "attachment"), constant("b", "second"));
        assertThat(result.data()).containsOnlyKeys("attachment").containsEntry("attachment", "first");
    }

    @Test
    @DisplayName("Null suite bindings or schema keep every variable a constant binding")
    void shouldKeepConstantsWithoutSuiteBindingsOrSchema() {
        final List<InputBindingDto> constants = List.of(constant("doc", "v"));

        assertThat(bindings.bindToFileColumns(constants, null, List.of()).bindings())
                .isEqualTo(constants);
        assertThat(bindings.bindToFileColumns(constants, List.of(column("doc", "attachment")), null)
                        .bindings())
                .isEqualTo(constants);
    }
}
