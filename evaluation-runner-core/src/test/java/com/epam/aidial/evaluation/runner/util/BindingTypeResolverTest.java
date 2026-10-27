package com.epam.aidial.evaluation.runner.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BindingTypeResolver")
class BindingTypeResolverTest {

    private static final List<FieldDefinitionDto> SCHEMA = List.of(
            FieldDefinitionDto.builder()
                    .name("attachment")
                    .type(SchemaFieldType.FILE)
                    .build(),
            FieldDefinitionDto.builder()
                    .name("skill")
                    .type(SchemaFieldType.SKILL)
                    .build(),
            FieldDefinitionDto.builder()
                    .name("title")
                    .type(SchemaFieldType.STRING)
                    .build());

    private final BindingTypeResolver resolver = new BindingTypeResolver();

    private static InputBindingDto dataBinding(String dataField) {
        return InputBindingDto.builder()
                .templateVariable("doc")
                .dataField(dataField)
                .build();
    }

    @Test
    @DisplayName("resolveType returns the column type for a known data field")
    void resolveTypeReturnsColumnType() {
        assertThat(resolver.resolveType(dataBinding("title"), SCHEMA)).contains(SchemaFieldType.STRING);
        assertThat(resolver.resolveType(dataBinding("attachment"), SCHEMA)).contains(SchemaFieldType.FILE);
        assertThat(resolver.resolveType(dataBinding("skill"), SCHEMA)).contains(SchemaFieldType.SKILL);
    }

    @Test
    @DisplayName("resolveType is empty for blank data field, unknown field, null binding or null/empty schema")
    void resolveTypeEmptyWhenUnresolvable() {
        assertThat(resolver.resolveType(dataBinding(" "), SCHEMA)).isEmpty();
        assertThat(resolver.resolveType(dataBinding("missing"), SCHEMA)).isEmpty();
        assertThat(resolver.resolveType(null, SCHEMA)).isEmpty();
        assertThat(resolver.resolveType(dataBinding("title"), null)).isEmpty();
        assertThat(resolver.resolveType(dataBinding("title"), List.of())).isEmpty();
    }

    @Test
    @DisplayName("resolveType is empty for a constantValue-only binding without dataField")
    void resolveTypeEmptyForConstantValueBinding() {
        final InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("doc")
                .constantValue("@ef/suites/abc/doc.pdf")
                .build();

        assertThat(resolver.resolveType(binding, SCHEMA)).isEmpty();
    }

    @Test
    @DisplayName("isOfType: explicit hint decides, case-insensitive and trimmed, and wins over the bound column")
    void isOfTypeExplicitHintDecides() {
        assertThat(resolver.isOfType(SchemaFieldType.FILE, " FILE ", null, null))
                .isTrue();
        assertThat(resolver.isOfType(SchemaFieldType.SKILL, "Skill", dataBinding("attachment"), SCHEMA))
                .isTrue();
        assertThat(resolver.isOfType(SchemaFieldType.FILE, "string", dataBinding("attachment"), SCHEMA))
                .isFalse();
        assertThat(resolver.isOfType(SchemaFieldType.FILE, "skill", null, null)).isFalse();
    }

    @Test
    @DisplayName("isOfType: a null or blank hint falls back to the bound column type")
    void isOfTypeFallsBackToBoundColumn() {
        assertThat(resolver.isOfType(SchemaFieldType.FILE, null, dataBinding("attachment"), SCHEMA))
                .isTrue();
        assertThat(resolver.isOfType(SchemaFieldType.FILE, "  ", dataBinding("attachment"), SCHEMA))
                .isTrue();
        assertThat(resolver.isOfType(SchemaFieldType.SKILL, null, dataBinding("attachment"), SCHEMA))
                .isFalse();
        assertThat(resolver.isOfType(SchemaFieldType.SKILL, null, dataBinding("skill"), SCHEMA))
                .isTrue();
    }

    @Test
    @DisplayName("isOfType: no hint and an unresolvable binding is not of the type")
    void isOfTypeFalseWhenUnresolvable() {
        assertThat(resolver.isOfType(SchemaFieldType.FILE, null, null, SCHEMA)).isFalse();
        assertThat(resolver.isOfType(SchemaFieldType.FILE, null, dataBinding("attachment"), null))
                .isFalse();
        assertThat(resolver.isOfType(SchemaFieldType.FILE, null, dataBinding("missing"), SCHEMA))
                .isFalse();
    }
}
