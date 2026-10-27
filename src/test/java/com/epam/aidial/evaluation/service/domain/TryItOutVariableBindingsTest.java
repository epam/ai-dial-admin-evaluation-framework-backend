package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.util.BindingTypeResolver;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TryItOutVariableBindingsTest {

    private final TryItOutVariableBindings bindings = new TryItOutVariableBindings(new BindingTypeResolver());

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
    @DisplayName("Variable bound to a FILE column in the suite keeps its constant value and gains the column as "
            + "dataField")
    void shouldBindFileColumnVariableToDataField() {
        final List<InputBindingDto> result = bindings.bindToFileColumns(
                List.of(constant("doc", "@ef/suites/x/a.pdf")),
                List.of(column("doc", "attachment")),
                List.of(field("attachment", SchemaFieldType.FILE)));

        assertThat(result).containsExactly(fileColumn("doc", "attachment", "@ef/suites/x/a.pdf"));
    }

    @Test
    @DisplayName("Variable bound to a SKILL column in the suite keeps its constant value and gains the column as "
            + "dataField")
    void shouldBindSkillColumnVariableToDataField() {
        final List<InputBindingDto> result = bindings.bindToFileColumns(
                List.of(constant("s", "skills/my-bucket/summariser")),
                List.of(column("s", "skillField")),
                List.of(field("skillField", SchemaFieldType.SKILL)));

        assertThat(result).containsExactly(fileColumn("s", "skillField", "skills/my-bucket/summariser"));
    }

    @Test
    @DisplayName("Variables sharing one SKILL column each keep their own value")
    void shouldKeepEachValueWhenVariablesShareSkillColumn() {
        final List<InputBindingDto> result = bindings.bindToFileColumns(
                List.of(constant("a", "skills/b/one"), constant("b", "skills/b/two")),
                List.of(column("a", "skillField"), column("b", "skillField")),
                List.of(field("skillField", SchemaFieldType.SKILL)));

        assertThat(result)
                .containsExactly(
                        fileColumn("a", "skillField", "skills/b/one"), fileColumn("b", "skillField", "skills/b/two"));
    }

    @Test
    @DisplayName("Variable bound to a non-FILE column, a constant or nothing stays a constant binding")
    void shouldKeepConstantBindingWhenNotFileColumn() {
        final List<InputBindingDto> constants =
                List.of(constant("title", "t"), constant("fixed", "f"), constant("free", "x"));

        final List<InputBindingDto> result = bindings.bindToFileColumns(
                constants,
                List.of(column("title", "title_col"), constant("fixed", "suiteConst")),
                List.of(field("title_col", SchemaFieldType.STRING)));

        assertThat(result).isEqualTo(constants);
    }

    @Test
    @DisplayName("Variables sharing one FILE column each keep their own value")
    void shouldKeepEachValueWhenVariablesShareFileColumn() {
        final List<InputBindingDto> result = bindings.bindToFileColumns(
                List.of(constant("a", "first"), constant("b", "second")),
                List.of(column("a", "attachment"), column("b", "attachment")),
                List.of(field("attachment", SchemaFieldType.FILE)));

        assertThat(result)
                .containsExactly(fileColumn("a", "attachment", "first"), fileColumn("b", "attachment", "second"));
    }

    @Test
    @DisplayName("Null suite bindings or schema keep every variable a constant binding")
    void shouldKeepConstantsWithoutSuiteBindingsOrSchema() {
        final List<InputBindingDto> constants = List.of(constant("doc", "v"));

        assertThat(bindings.bindToFileColumns(constants, null, List.of())).isEqualTo(constants);
        assertThat(bindings.bindToFileColumns(constants, List.of(column("doc", "attachment")), null))
                .isEqualTo(constants);
    }

    private static InputBindingDto fileColumn(String variable, String dataField, Object value) {
        return InputBindingDto.builder()
                .templateVariable(variable)
                .dataField(dataField)
                .constantValue(value)
                .build();
    }
}
