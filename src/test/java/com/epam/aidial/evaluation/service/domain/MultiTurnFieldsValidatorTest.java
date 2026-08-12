package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MultiTurnFieldsValidatorTest {

    // Schema: prompt is per-turn (varies between turns), tags is shared (test-case-level, constant).
    private static final List<FieldDefinitionDto> SCHEMA = List.of(
            FieldDefinitionDto.builder()
                    .name("prompt")
                    .type(SchemaFieldType.STRING)
                    .perTurn(true)
                    .build(),
            FieldDefinitionDto.builder()
                    .name("tags")
                    .type(SchemaFieldType.STRING)
                    .perTurn(false)
                    .build());

    private MultiTurnFieldsValidator validator;

    @BeforeEach
    void setUp() {
        TestCaseProperties props = new TestCaseProperties();
        props.getMultiTurn().setMaxTurns(10);
        validator =
                new MultiTurnFieldsValidator(props, new TestCaseDataScopeResolver(new TestCaseFieldScopeResolver()));
    }

    @Test
    @DisplayName("shared data and per-turn multiTurnData coexist")
    void coexistAccepted() {
        assertThatCode(() -> validator.validateStructure(
                        Map.of("tags", "a"), List.of(Map.of("prompt", "hi"), Map.of("prompt", "again")), SCHEMA))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("empty multiTurnData array is rejected")
    void emptyArrayRejected() {
        assertThatThrownBy(() -> validator.validateStructure(Map.of(), List.of(), SCHEMA))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("a per-turn field placed in shared data is rejected")
    void perTurnFieldInDataRejected() {
        assertThatThrownBy(() ->
                        validator.validateStructure(Map.of("prompt", "x"), List.of(Map.of("prompt", "hi")), SCHEMA))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Field 'prompt' is per-turn but currently specified on a test case level. Re-create "
                        + "column for correct data attachment");
    }

    @Test
    @DisplayName("a shared field placed in a turn map is rejected")
    void sharedFieldInTurnRejected() {
        assertThatThrownBy(() ->
                        validator.validateStructure(Map.of(), List.of(Map.of("prompt", "hi", "tags", "a")), SCHEMA))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Field 'tags' is shared (test-case-level) but values are specified on turn level. "
                        + "Re-create column for correct data attachment");
    }

    @Test
    @DisplayName("single-turn (multiTurnData null) skips placement checks")
    void singleTurnNoPlacementCheck() {
        assertThatCode(() -> validator.validateStructure(Map.of("prompt", "hi", "tags", "a"), null, SCHEMA))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("getMaxTurns exposes the configured cap")
    void exposesCap() {
        assertThat(validator.getMaxTurns()).isEqualTo(10);
    }
}
