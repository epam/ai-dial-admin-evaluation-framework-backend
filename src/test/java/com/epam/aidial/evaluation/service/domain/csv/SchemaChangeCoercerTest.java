package com.epam.aidial.evaluation.service.domain.csv;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.service.domain.csv.SchemaChangeCoercer.CoercionResult;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SchemaChangeCoercer")
class SchemaChangeCoercerTest {

    private final SchemaChangeCoercer coercer = new SchemaChangeCoercer();

    @Nested
    @DisplayName("STRING target")
    class StringTarget {

        @Test
        @DisplayName("Boolean true → \"true\"")
        void booleanTrueToString() {
            assertThat(coercer.coerce(true, SchemaFieldType.STRING)).isEqualTo("true");
        }

        @Test
        @DisplayName("Boolean false → \"false\"")
        void booleanFalseToString() {
            assertThat(coercer.coerce(false, SchemaFieldType.STRING)).isEqualTo("false");
        }

        @Test
        @DisplayName("Long → String")
        void longToString() {
            assertThat(coercer.coerce(1865L, SchemaFieldType.STRING)).isEqualTo("1865");
        }

        @Test
        @DisplayName("Integer → String")
        void integerToString() {
            assertThat(coercer.coerce(42, SchemaFieldType.STRING)).isEqualTo("42");
        }

        @Test
        @DisplayName("Double → String")
        void doubleToString() {
            assertThat(coercer.coerce(3.14, SchemaFieldType.STRING)).isEqualTo("3.14");
        }

        @Test
        @DisplayName("String identity")
        void stringIdentity() {
            String input = "hello";
            assertThat(coercer.coerce(input, SchemaFieldType.STRING)).isSameAs(input);
        }

        @Test
        @DisplayName("Map → STRING is skipped (no debug-form stringification)")
        void mapToStringSkipped() {
            Map<String, Object> input = Map.of("a", 1);
            assertThat(coercer.coerce(input, SchemaFieldType.STRING)).isSameAs(input);
        }

        @Test
        @DisplayName("List → STRING is skipped")
        void listToStringSkipped() {
            List<Integer> input = List.of(1, 2, 3);
            assertThat(coercer.coerce(input, SchemaFieldType.STRING)).isSameAs(input);
        }
    }

    @Nested
    @DisplayName("INTEGER target")
    class IntegerTarget {

        @Test
        @DisplayName("Long identity")
        void longIdentity() {
            Object result = coercer.coerce(42L, SchemaFieldType.INTEGER);
            assertThat(result).isEqualTo(42L).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("Integer → Long")
        void integerToLong() {
            Object result = coercer.coerce(42, SchemaFieldType.INTEGER);
            assertThat(result).isEqualTo(42L).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("String parseable → Long")
        void stringParseableToLong() {
            Object result = coercer.coerce("1865", SchemaFieldType.INTEGER);
            assertThat(result).isEqualTo(1865L).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("Whole-number Double → Long")
        void wholeDoubleToLong() {
            Object result = coercer.coerce(7.0, SchemaFieldType.INTEGER);
            assertThat(result).isEqualTo(7L).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("Fractional Double → skipped (returns Double unchanged)")
        void fractionalDoubleSkipped() {
            Object input = 3.14;
            Object result = coercer.coerce(input, SchemaFieldType.INTEGER);
            assertThat(result).isSameAs(input);
        }

        @Test
        @DisplayName("String non-numeric → skipped")
        void stringNonNumericSkipped() {
            Object input = "hello";
            assertThat(coercer.coerce(input, SchemaFieldType.INTEGER)).isSameAs(input);
        }

        @Test
        @DisplayName("Boolean → INTEGER is skipped (stricter than CSV import)")
        void booleanToIntegerSkipped() {
            Object input = Boolean.TRUE;
            assertThat(coercer.coerce(input, SchemaFieldType.INTEGER)).isSameAs(input);
        }
    }

    @Nested
    @DisplayName("NUMBER target")
    class NumberTarget {

        @Test
        @DisplayName("Double identity")
        void doubleIdentity() {
            Object result = coercer.coerce(3.14, SchemaFieldType.NUMBER);
            assertThat(result).isEqualTo(3.14).isInstanceOf(Double.class);
        }

        @Test
        @DisplayName("Long → Double")
        void longToDouble() {
            Object result = coercer.coerce(42L, SchemaFieldType.NUMBER);
            assertThat(result).isEqualTo(42.0).isInstanceOf(Double.class);
        }

        @Test
        @DisplayName("Integer → Double")
        void integerToDouble() {
            Object result = coercer.coerce(42, SchemaFieldType.NUMBER);
            assertThat(result).isEqualTo(42.0).isInstanceOf(Double.class);
        }

        @Test
        @DisplayName("String parseable → Double")
        void stringParseableToDouble() {
            Object result = coercer.coerce("3.14", SchemaFieldType.NUMBER);
            assertThat(result).isEqualTo(3.14).isInstanceOf(Double.class);
        }

        @Test
        @DisplayName("Boolean → NUMBER is skipped (stricter than CSV import)")
        void booleanToNumberSkipped() {
            Object input = Boolean.FALSE;
            assertThat(coercer.coerce(input, SchemaFieldType.NUMBER)).isSameAs(input);
        }
    }

    @Nested
    @DisplayName("BOOLEAN target")
    class BooleanTarget {

        @Test
        @DisplayName("Boolean identity")
        void booleanIdentity() {
            assertThat(coercer.coerce(Boolean.TRUE, SchemaFieldType.BOOLEAN)).isSameAs(Boolean.TRUE);
            assertThat(coercer.coerce(Boolean.FALSE, SchemaFieldType.BOOLEAN)).isSameAs(Boolean.FALSE);
        }

        @Test
        @DisplayName("String \"true\" → Boolean.TRUE")
        void stringTrueToBoolean() {
            assertThat(coercer.coerce("true", SchemaFieldType.BOOLEAN)).isEqualTo(Boolean.TRUE);
        }

        @Test
        @DisplayName("String \"false\" → Boolean.FALSE")
        void stringFalseToBoolean() {
            assertThat(coercer.coerce("false", SchemaFieldType.BOOLEAN)).isEqualTo(Boolean.FALSE);
        }

        @Test
        @DisplayName("String \"yes\" → skipped (only \"true\"/\"false\" coerce)")
        void stringYesSkipped() {
            Object input = "yes";
            assertThat(coercer.coerce(input, SchemaFieldType.BOOLEAN)).isSameAs(input);
        }

        @Test
        @DisplayName("String \"TRUE\" mixed-case → skipped (strict literal match)")
        void stringMixedCaseSkipped() {
            Object input = "TRUE";
            assertThat(coercer.coerce(input, SchemaFieldType.BOOLEAN)).isSameAs(input);
        }

        @Test
        @DisplayName("Integer 1 → BOOLEAN is skipped (stricter than CSV import)")
        void integerToBooleanSkipped() {
            Object input = 1;
            assertThat(coercer.coerce(input, SchemaFieldType.BOOLEAN)).isSameAs(input);
        }

        @Test
        @DisplayName("Long 0 → BOOLEAN is skipped")
        void longZeroToBooleanSkipped() {
            Object input = 0L;
            assertThat(coercer.coerce(input, SchemaFieldType.BOOLEAN)).isSameAs(input);
        }

        @Test
        @DisplayName("Double → BOOLEAN is skipped")
        void doubleToBooleanSkipped() {
            Object input = 1.0;
            assertThat(coercer.coerce(input, SchemaFieldType.BOOLEAN)).isSameAs(input);
        }
    }

    @Nested
    @DisplayName("FILE target")
    class FileTarget {

        @Test
        @DisplayName("String identity")
        void stringIdentity() {
            String input = "@ef/suites/abc/file.png";
            assertThat(coercer.coerce(input, SchemaFieldType.FILE)).isSameAs(input);
        }

        @Test
        @DisplayName("Boolean → FILE is skipped")
        void booleanToFileSkipped() {
            Object input = Boolean.TRUE;
            assertThat(coercer.coerce(input, SchemaFieldType.FILE)).isSameAs(input);
        }

        @Test
        @DisplayName("Long → FILE is skipped")
        void longToFileSkipped() {
            Object input = 42L;
            assertThat(coercer.coerce(input, SchemaFieldType.FILE)).isSameAs(input);
        }

        @Test
        @DisplayName("Double → FILE is skipped")
        void doubleToFileSkipped() {
            Object input = 3.14;
            assertThat(coercer.coerce(input, SchemaFieldType.FILE)).isSameAs(input);
        }

        @Test
        @DisplayName("Map → FILE is skipped")
        void mapToFileSkipped() {
            Object input = Map.of("a", 1);
            assertThat(coercer.coerce(input, SchemaFieldType.FILE)).isSameAs(input);
        }

        @Test
        @DisplayName("List → FILE is skipped")
        void listToFileSkipped() {
            Object input = List.of("a");
            assertThat(coercer.coerce(input, SchemaFieldType.FILE)).isSameAs(input);
        }
    }

    @Nested
    @DisplayName("Null and OBJECT/ARRAY")
    class NullAndContainerTargets {

        @Test
        @DisplayName("Null → identity for every target type")
        void nullIdentity() {
            for (SchemaFieldType type : SchemaFieldType.values()) {
                assertThat(coercer.coerce(null, type)).as("target=%s", type).isNull();
            }
        }

        @Test
        @DisplayName("Null target type → identity")
        void nullTargetIdentity() {
            assertThat(coercer.coerce(42L, null)).isEqualTo(42L);
            assertThat(coercer.coerce("hello", null)).isEqualTo("hello");
        }

        @Test
        @DisplayName("Map → OBJECT identity")
        void mapToObjectIdentity() {
            Map<String, Object> input = Map.of("a", 1);
            assertThat(coercer.coerce(input, SchemaFieldType.OBJECT)).isSameAs(input);
        }

        @Test
        @DisplayName("List → ARRAY identity")
        void listToArrayIdentity() {
            List<Integer> input = List.of(1, 2);
            assertThat(coercer.coerce(input, SchemaFieldType.ARRAY)).isSameAs(input);
        }

        @Test
        @DisplayName("String → OBJECT is skipped (no parsing)")
        void stringToObjectSkipped() {
            Object input = "{\"a\":1}";
            assertThat(coercer.coerce(input, SchemaFieldType.OBJECT)).isSameAs(input);
        }
    }

    @Nested
    @DisplayName("coerceMap")
    class CoerceMapBehavior {

        @Test
        @DisplayName("Empty data → no coercion, changed=false, count=0")
        void emptyDataNoOp() {
            CoercionResult result = coercer.coerceMap(Map.of(), List.of(field("f", SchemaFieldType.STRING)));
            assertThat(result.changed()).isFalse();
            assertThat(result.coercedCellCount()).isZero();
        }

        @Test
        @DisplayName("Null schema → no coercion")
        void nullSchemaNoOp() {
            Map<String, Object> data = new HashMap<>();
            data.put("f", true);
            CoercionResult result = coercer.coerceMap(data, null);
            assertThat(result.changed()).isFalse();
            assertThat(result.coercedCellCount()).isZero();
        }

        @Test
        @DisplayName("All cells already match types → changed=false, count=0")
        void allMatchingNoOp() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("s", "hello");
            data.put("n", 42L);
            CoercionResult result = coercer.coerceMap(
                    data, List.of(field("s", SchemaFieldType.STRING), field("n", SchemaFieldType.INTEGER)));
            assertThat(result.changed()).isFalse();
            assertThat(result.coercedCellCount()).isZero();
        }

        @Test
        @DisplayName("Single Boolean→STRING coercion → count=1, changed=true")
        void singleBooleanToString() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("flag", true);
            CoercionResult result = coercer.coerceMap(data, List.of(field("flag", SchemaFieldType.STRING)));
            assertThat(result.changed()).isTrue();
            assertThat(result.coercedCellCount()).isEqualTo(1);
            assertThat(result.coercedData()).containsEntry("flag", "true");
        }

        @Test
        @DisplayName("Multi-field coercion accumulates cell count")
        void multiFieldAccumulates() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("s", true); // Boolean → STRING: coerce
            data.put("n", "42"); // String → INTEGER: coerce
            data.put("d", 7.0); // Double → INTEGER: coerce (whole)
            data.put("b", "true"); // String → BOOLEAN: coerce
            data.put("frac", 3.14); // Double → INTEGER: skip (fractional)
            data.put("untouched", "stays"); // String → STRING: identity
            CoercionResult result = coercer.coerceMap(
                    data,
                    List.of(
                            field("s", SchemaFieldType.STRING),
                            field("n", SchemaFieldType.INTEGER),
                            field("d", SchemaFieldType.INTEGER),
                            field("b", SchemaFieldType.BOOLEAN),
                            field("frac", SchemaFieldType.INTEGER),
                            field("untouched", SchemaFieldType.STRING)));
            assertThat(result.coercedCellCount()).isEqualTo(4);
            assertThat(result.changed()).isTrue();
            assertThat(result.coercedData()).containsEntry("s", "true");
            assertThat(result.coercedData()).containsEntry("n", 42L);
            assertThat(result.coercedData()).containsEntry("d", 7L);
            assertThat(result.coercedData()).containsEntry("b", Boolean.TRUE);
            assertThat(result.coercedData()).containsEntry("frac", 3.14);
            assertThat(result.coercedData()).containsEntry("untouched", "stays");
        }

        @Test
        @DisplayName("Field present in data but absent in schema → ignored")
        void unknownFieldIgnored() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("known", true);
            data.put("unknown", true);
            CoercionResult result = coercer.coerceMap(data, List.of(field("known", SchemaFieldType.STRING)));
            assertThat(result.coercedCellCount()).isEqualTo(1);
            assertThat(result.coercedData()).containsEntry("known", "true");
            assertThat(result.coercedData()).containsEntry("unknown", true);
        }

        @Test
        @DisplayName("Idempotent: re-running on already-coerced data yields count=0")
        void idempotent() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("flag", true);
            List<FieldDefinitionDto> schema = List.of(field("flag", SchemaFieldType.STRING));

            CoercionResult firstRun = coercer.coerceMap(data, schema);
            assertThat(firstRun.coercedCellCount()).isEqualTo(1);

            CoercionResult secondRun = coercer.coerceMap(firstRun.coercedData(), schema);
            assertThat(secondRun.coercedCellCount()).isZero();
            assertThat(secondRun.changed()).isFalse();
        }

        @Test
        @DisplayName("Skipped cell stays unchanged in result map")
        void skippedCellPreserved() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("nope", List.of(1, 2, 3));
            CoercionResult result = coercer.coerceMap(data, List.of(field("nope", SchemaFieldType.STRING)));
            assertThat(result.changed()).isFalse();
            assertThat(result.coercedCellCount()).isZero();
            assertThat(result.coercedData()).containsEntry("nope", List.of(1, 2, 3));
        }
    }

    private static FieldDefinitionDto field(String name, SchemaFieldType type) {
        return FieldDefinitionDto.builder()
                .name(name)
                .type(type)
                .required(false)
                .build();
    }
}
