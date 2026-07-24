package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TestCaseFieldScopeResolver")
class TestCaseFieldScopeResolverTest {

    private final TestCaseFieldScopeResolver resolver = new TestCaseFieldScopeResolver();

    private static final List<FieldDefinitionDto> SCHEMA =
            List.of(field("prompt", true), field("category", true), field("tags", false), field("system", false));

    private static FieldDefinitionDto field(String name, boolean perTurn) {
        return FieldDefinitionDto.builder()
                .name(name)
                .type(SchemaFieldType.STRING)
                .perTurn(perTurn)
                .build();
    }

    @Test
    @DisplayName("splits field names by scope")
    void scopeSets() {
        assertThat(resolver.perTurnFieldNames(SCHEMA)).containsExactlyInAnyOrder("prompt", "category");
        assertThat(resolver.sharedFieldNames(SCHEMA)).containsExactlyInAnyOrder("tags", "system");
    }

    @Test
    @DisplayName("partition routes known fields to their bucket; unknown keys stay shared")
    void partition() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("prompt", "hi");
        flat.put("tags", "a");
        flat.put("mystery", 1);

        TestCaseFieldScopeResolver.Partition p = resolver.partition(flat, SCHEMA);
        assertThat(p.perTurn()).containsOnlyKeys("prompt");
        assertThat(p.shared()).containsOnlyKeys("tags", "mystery");
    }

    @Test
    @DisplayName("absent perTurn defaults to shared")
    void defaultsShared() {
        List<FieldDefinitionDto> legacy = List.of(FieldDefinitionDto.builder()
                .name("prompt")
                .type(SchemaFieldType.STRING)
                .build());
        assertThat(resolver.sharedFieldNames(legacy)).containsExactly("prompt");
        assertThat(resolver.perTurnFieldNames(legacy)).isEmpty();
    }
}
