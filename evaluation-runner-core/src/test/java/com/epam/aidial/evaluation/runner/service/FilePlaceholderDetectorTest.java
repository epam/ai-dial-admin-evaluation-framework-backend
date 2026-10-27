package com.epam.aidial.evaluation.runner.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FilePlaceholderDetector")
class FilePlaceholderDetectorTest {

    private final FilePlaceholderDetector detector = new FilePlaceholderDetector();

    private static final List<FieldDefinitionDto> SCHEMA = List.of(
            FieldDefinitionDto.builder()
                    .name("attachment")
                    .type(SchemaFieldType.FILE)
                    .build(),
            FieldDefinitionDto.builder()
                    .name("title")
                    .type(SchemaFieldType.STRING)
                    .build());

    private static InputBindingDto dataBinding(String dataField) {
        return InputBindingDto.builder()
                .templateVariable("doc")
                .dataField(dataField)
                .build();
    }

    @Test
    @DisplayName("hintless placeholder bound to a FILE column is a file reference")
    void hintlessBoundToFileColumnIsFile() {
        assertThat(detector.isFileReference(null, dataBinding("attachment"), SCHEMA))
                .isTrue();
    }

    @Test
    @DisplayName("hintless placeholder bound to a STRING column is not a file reference")
    void hintlessBoundToStringColumnIsNotFile() {
        assertThat(detector.isFileReference(null, dataBinding("title"), SCHEMA)).isFalse();
    }

    @Test
    @DisplayName("explicit file hint is a file reference without a binding or schema")
    void explicitFileHintIsFileWithoutSchema() {
        assertThat(detector.isFileReference("file", null, null)).isTrue();
    }

    @Test
    @DisplayName("file hint is case-insensitive and trimmed")
    void fileHintIsCaseInsensitiveAndTrimmed() {
        assertThat(detector.isFileReference(" FILE ", null, SCHEMA)).isTrue();
        assertThat(detector.isFileReference("File", dataBinding("title"), SCHEMA))
                .isTrue();
    }

    @Test
    @DisplayName("explicit non-file hint wins over a FILE column")
    void explicitNonFileHintWinsOverFileColumn() {
        assertThat(detector.isFileReference("string", dataBinding("attachment"), SCHEMA))
                .isFalse();
    }

    @Test
    @DisplayName("blank hint is treated as absent")
    void blankHintIsTreatedAsAbsent() {
        assertThat(detector.isFileReference("  ", dataBinding("attachment"), SCHEMA))
                .isTrue();
    }

    @Test
    @DisplayName("constantValue-only binding without hint is not a file reference")
    void constantValueBindingWithoutHintIsNotFile() {
        final InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("doc")
                .constantValue("@ef/suites/abc/doc.pdf")
                .build();

        assertThat(detector.isFileReference(null, binding, SCHEMA)).isFalse();
    }

    @Test
    @DisplayName("null or empty schema yields not a file reference when no hint")
    void nullOrEmptySchemaIsNotFile() {
        assertThat(detector.isFileReference(null, dataBinding("attachment"), null))
                .isFalse();
        assertThat(detector.isFileReference(null, dataBinding("attachment"), List.of()))
                .isFalse();
    }

    @Test
    @DisplayName("unknown data field yields not a file reference")
    void unknownDataFieldIsNotFile() {
        assertThat(detector.isFileReference(null, dataBinding("missing"), SCHEMA))
                .isFalse();
    }

    @Test
    @DisplayName("no binding yields not a file reference when no hint")
    void noBindingIsNotFile() {
        assertThat(detector.isFileReference(null, null, SCHEMA)).isFalse();
    }

    @Test
    @DisplayName("boundFieldType returns the column type for a known data field")
    void boundFieldTypeReturnsColumnType() {
        assertThat(detector.boundFieldType(dataBinding("title"), SCHEMA)).contains(SchemaFieldType.STRING);
        assertThat(detector.boundFieldType(dataBinding("attachment"), SCHEMA)).contains(SchemaFieldType.FILE);
    }

    @Test
    @DisplayName("boundFieldType is empty for blank data field, unknown field, null binding or null schema")
    void boundFieldTypeEmptyWhenUnresolvable() {
        assertThat(detector.boundFieldType(dataBinding(" "), SCHEMA)).isEmpty();
        assertThat(detector.boundFieldType(dataBinding("missing"), SCHEMA)).isEmpty();
        assertThat(detector.boundFieldType(null, SCHEMA)).isEmpty();
        assertThat(detector.boundFieldType(dataBinding("title"), null)).isEmpty();
    }

    private static final List<FieldDefinitionDto> SKILL_SCHEMA = List.of(
            FieldDefinitionDto.builder()
                    .name("skill")
                    .type(SchemaFieldType.SKILL)
                    .build(),
            FieldDefinitionDto.builder()
                    .name("attachment")
                    .type(SchemaFieldType.FILE)
                    .build());

    @Test
    @DisplayName("hintless placeholder bound to a SKILL column is a skill reference and not a file reference")
    void hintlessBoundToSkillColumnIsSkill() {
        assertThat(detector.isSkillReference(null, dataBinding("skill"), SKILL_SCHEMA))
                .isTrue();
        assertThat(detector.isFileReference(null, dataBinding("skill"), SKILL_SCHEMA))
                .isFalse();
    }

    @Test
    @DisplayName("hintless placeholder bound to a FILE column is not a skill reference")
    void hintlessBoundToFileColumnIsNotSkill() {
        assertThat(detector.isSkillReference(null, dataBinding("attachment"), SKILL_SCHEMA))
                .isFalse();
    }

    @Test
    @DisplayName("explicit skill hint is a skill reference without a binding or schema")
    void explicitSkillHintIsSkillWithoutSchema() {
        assertThat(detector.isSkillReference("skill", null, null)).isTrue();
    }

    @Test
    @DisplayName("skill hint is case-insensitive and trimmed")
    void skillHintIsCaseInsensitiveAndTrimmed() {
        assertThat(detector.isSkillReference(" SKILL ", null, SKILL_SCHEMA)).isTrue();
        assertThat(detector.isSkillReference("Skill", dataBinding("attachment"), SKILL_SCHEMA))
                .isTrue();
    }

    @Test
    @DisplayName("explicit non-skill hint wins over a SKILL column")
    void explicitNonSkillHintWinsOverSkillColumn() {
        assertThat(detector.isSkillReference("string", dataBinding("skill"), SKILL_SCHEMA))
                .isFalse();
        assertThat(detector.isSkillReference("file", dataBinding("skill"), SKILL_SCHEMA))
                .isFalse();
    }

    @Test
    @DisplayName("constantValue-only binding without hint is not a skill reference")
    void constantValueBindingWithoutHintIsNotSkill() {
        final InputBindingDto binding = InputBindingDto.builder()
                .templateVariable("s")
                .constantValue("skills/b/x")
                .build();

        assertThat(detector.isSkillReference(null, binding, SKILL_SCHEMA)).isFalse();
    }

    @Test
    @DisplayName("null schema or no binding yields not a skill reference when no hint")
    void nullSchemaOrNoBindingIsNotSkill() {
        assertThat(detector.isSkillReference(null, dataBinding("skill"), null)).isFalse();
        assertThat(detector.isSkillReference(null, null, SKILL_SCHEMA)).isFalse();
    }

    @Test
    @DisplayName("explicit skill hint is not a file reference")
    void explicitSkillHintIsNotFile() {
        assertThat(detector.isFileReference("skill", null, null)).isFalse();
    }
}
