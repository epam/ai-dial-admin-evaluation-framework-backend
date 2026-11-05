package com.epam.aidial.evaluation.service.domain.csv;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.client.dialcore.SkillRefResolver;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.util.BindingTypeResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@DisplayName("CsvColumnTypeInferrer")
class CsvColumnTypeInferrerTest {

    private final CsvColumnTypeInferrer inferrer =
            new CsvColumnTypeInferrer(new ObjectMapper(), new SkillRefResolver(new BindingTypeResolver()));

    @Nested
    @DisplayName("inferCellType — existing types (regression)")
    class ExistingTypes {

        @Test
        @DisplayName("a JSON object is OBJECT")
        void jsonObjectIsObject() {
            assertThat(inferrer.inferCellType("{\"key\":\"value\"}")).isEqualTo(SchemaFieldType.OBJECT);
        }

        @Test
        @DisplayName("a JSON array is ARRAY")
        void jsonArrayIsArray() {
            assertThat(inferrer.inferCellType("[\"a\",\"b\"]")).isEqualTo(SchemaFieldType.ARRAY);
        }

        @ParameterizedTest(name = "\"{0}\" is STRING")
        @ValueSource(strings = {"{not json", "[not json"})
        @DisplayName("an object/array-looking cell that is not valid JSON is STRING")
        void invalidJsonIsString(String value) {
            assertThat(inferrer.inferCellType(value)).isEqualTo(SchemaFieldType.STRING);
        }

        @ParameterizedTest(name = "\"{0}\" is BOOLEAN")
        @ValueSource(strings = {"true", "FALSE", "True"})
        @DisplayName("literal true/false, case-insensitive, is BOOLEAN")
        void booleanLiteralIsBoolean(String value) {
            assertThat(inferrer.inferCellType(value)).isEqualTo(SchemaFieldType.BOOLEAN);
        }

        @ParameterizedTest(name = "\"{0}\" is INTEGER")
        @ValueSource(strings = {"1", "0", "-42", "3000000000"})
        @DisplayName("a whole number (including 1/0) is INTEGER, never BOOLEAN")
        void wholeNumberIsInteger(String value) {
            assertThat(inferrer.inferCellType(value)).isEqualTo(SchemaFieldType.INTEGER);
        }

        @ParameterizedTest(name = "\"{0}\" is NUMBER")
        @ValueSource(strings = {"3.14", "-0.5", "3."})
        @DisplayName("a decimal number is NUMBER")
        void decimalIsNumber(String value) {
            assertThat(inferrer.inferCellType(value)).isEqualTo(SchemaFieldType.NUMBER);
        }

        @Test
        @DisplayName("free text is STRING")
        void freeTextIsString() {
            assertThat(inferrer.inferCellType("hello")).isEqualTo(SchemaFieldType.STRING);
        }
    }

    @Nested
    @DisplayName("inferCellType — SKILL")
    class Skill {

        @ParameterizedTest(name = "\"{0}\" is SKILL")
        @ValueSource(strings = {"skills/b/x", "skills/b/team/x", "skills/my-bucket/summariser/"})
        @DisplayName("a well-formed skill reference (one trailing slash tolerated) is SKILL")
        void wellFormedReferenceIsSkill(String value) {
            assertThat(inferrer.inferCellType(value)).isEqualTo(SchemaFieldType.SKILL);
        }

        @ParameterizedTest(name = "\"{0}\" is STRING")
        @ValueSource(
                strings = {
                    "skills/b",
                    "skills/b/",
                    "Skills/b/x",
                    "files/b/x",
                    "/skills/b/x",
                    "skills/b/../x",
                    "skills/b/./x",
                    "skills/b//x",
                    "skills/b\\x/y"
                })
        @DisplayName("a malformed skill reference is STRING, never SKILL")
        void malformedReferenceIsString(String value) {
            assertThat(inferrer.inferCellType(value)).isEqualTo(SchemaFieldType.STRING);
        }
    }

    @Nested
    @DisplayName("widen")
    class Widen {

        @Test
        @DisplayName("SKILL + SKILL stays SKILL")
        void skillWithSkillStaysSkill() {
            assertThat(CsvColumnTypeInferrer.widen(SchemaFieldType.SKILL, SchemaFieldType.SKILL))
                    .isEqualTo(SchemaFieldType.SKILL);
        }

        @Test
        @DisplayName("SKILL + STRING widens to STRING")
        void skillWithStringIsString() {
            assertThat(CsvColumnTypeInferrer.widen(SchemaFieldType.SKILL, SchemaFieldType.STRING))
                    .isEqualTo(SchemaFieldType.STRING);
        }

        @Test
        @DisplayName("SKILL + INTEGER widens to STRING")
        void skillWithIntegerIsString() {
            assertThat(CsvColumnTypeInferrer.widen(SchemaFieldType.INTEGER, SchemaFieldType.SKILL))
                    .isEqualTo(SchemaFieldType.STRING);
        }

        @Test
        @DisplayName("INTEGER + NUMBER widens to NUMBER")
        void integerWithNumberIsNumber() {
            assertThat(CsvColumnTypeInferrer.widen(SchemaFieldType.INTEGER, SchemaFieldType.NUMBER))
                    .isEqualTo(SchemaFieldType.NUMBER);
        }

        @Test
        @DisplayName("INTEGER + BOOLEAN widens to STRING")
        void integerWithBooleanIsString() {
            assertThat(CsvColumnTypeInferrer.widen(SchemaFieldType.INTEGER, SchemaFieldType.BOOLEAN))
                    .isEqualTo(SchemaFieldType.STRING);
        }
    }
}
