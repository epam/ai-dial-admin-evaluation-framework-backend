package com.epam.aidial.evaluation.service.domain.csv;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SchemaTypeCoercer")
class SchemaTypeCoercerTest {

    private final SchemaTypeCoercer coercer = new SchemaTypeCoercer();

    // -------------------------------------------------------------------------
    // STRING coercion
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("STRING schema type")
    class StringCoercion {

        @Test
        @DisplayName("String value — no-op")
        void stringNoOp() {
            assertThat(coercer.coerce("hello", SchemaFieldType.STRING)).isEqualTo("hello");
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
        @DisplayName("Boolean true → String \"true\"")
        void booleanTrueToString() {
            assertThat(coercer.coerce(true, SchemaFieldType.STRING)).isEqualTo("true");
        }

        @Test
        @DisplayName("Boolean false → String \"false\"")
        void booleanFalseToString() {
            assertThat(coercer.coerce(false, SchemaFieldType.STRING)).isEqualTo("false");
        }
    }

    // -------------------------------------------------------------------------
    // INTEGER coercion
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("INTEGER schema type")
    class IntegerCoercion {

        @Test
        @DisplayName("Long value — no-op")
        void longNoOp() {
            Object result = coercer.coerce(42L, SchemaFieldType.INTEGER);
            assertThat(result).isEqualTo(42L);
            assertThat(result).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("Integer → widened to Long")
        void integerWidenedToLong() {
            Object result = coercer.coerce(42, SchemaFieldType.INTEGER);
            assertThat(result).isEqualTo(42L);
            assertThat(result).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("String numeric → Long.parseLong")
        void stringNumericToLong() {
            Object result = coercer.coerce("1865", SchemaFieldType.INTEGER);
            assertThat(result).isEqualTo(1865L);
            assertThat(result).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("String large integer → Long(3000000000)")
        void stringLargeIntegerToLong() {
            Object result = coercer.coerce("3000000000", SchemaFieldType.INTEGER);
            assertThat(result).isEqualTo(3_000_000_000L);
            assertThat(result).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("Double whole number (3.0) → Long(3)")
        void doubleWholeToLong() {
            Object result = coercer.coerce(3.0, SchemaFieldType.INTEGER);
            assertThat(result).isEqualTo(3L);
            assertThat(result).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("Double fractional (3.14) — coercion failure, returns Double unchanged")
        void doubleFractionalCoercionFailure() {
            Object result = coercer.coerce(3.14, SchemaFieldType.INTEGER);
            assertThat(result).isEqualTo(3.14);
            assertThat(result).isInstanceOf(Double.class);
        }

        @Test
        @DisplayName("Boolean true → Long(1)")
        void booleanTrueToLong() {
            Object result = coercer.coerce(true, SchemaFieldType.INTEGER);
            assertThat(result).isEqualTo(1L);
        }

        @Test
        @DisplayName("Boolean false → Long(0)")
        void booleanFalseToLong() {
            Object result = coercer.coerce(false, SchemaFieldType.INTEGER);
            assertThat(result).isEqualTo(0L);
        }

        @Test
        @DisplayName("String non-numeric — coercion failure, returns String unchanged")
        void stringNonNumericCoercionFailure() {
            assertThat(coercer.coerce("hello", SchemaFieldType.INTEGER)).isEqualTo("hello");
        }

        @Test
        @DisplayName("Empty string — coercion failure, returns empty string unchanged")
        void emptyStringCoercionFailure() {
            assertThat(coercer.coerce("", SchemaFieldType.INTEGER)).isEqualTo("");
        }
    }

    // -------------------------------------------------------------------------
    // NUMBER coercion
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("NUMBER schema type")
    class NumberCoercion {

        @Test
        @DisplayName("Double value — no-op")
        void doubleNoOp() {
            Object result = coercer.coerce(3.14, SchemaFieldType.NUMBER);
            assertThat(result).isEqualTo(3.14);
            assertThat(result).isInstanceOf(Double.class);
        }

        @Test
        @DisplayName("Long → Double")
        void longToDouble() {
            Object result = coercer.coerce(42L, SchemaFieldType.NUMBER);
            assertThat(result).isEqualTo(42.0);
            assertThat(result).isInstanceOf(Double.class);
        }

        @Test
        @DisplayName("Integer → Double")
        void integerToDouble() {
            Object result = coercer.coerce(42, SchemaFieldType.NUMBER);
            assertThat(result).isEqualTo(42.0);
            assertThat(result).isInstanceOf(Double.class);
        }

        @Test
        @DisplayName("String numeric → Double")
        void stringNumericToDouble() {
            Object result = coercer.coerce("3.14", SchemaFieldType.NUMBER);
            assertThat(result).isEqualTo(3.14);
            assertThat(result).isInstanceOf(Double.class);
        }

        @Test
        @DisplayName("Boolean true → 1.0")
        void booleanTrueToDouble() {
            Object result = coercer.coerce(true, SchemaFieldType.NUMBER);
            assertThat(result).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Boolean false → 0.0")
        void booleanFalseToDouble() {
            Object result = coercer.coerce(false, SchemaFieldType.NUMBER);
            assertThat(result).isEqualTo(0.0);
        }

        @Test
        @DisplayName("String non-numeric — coercion failure, returns String unchanged")
        void stringNonNumericCoercionFailure() {
            assertThat(coercer.coerce("hello", SchemaFieldType.NUMBER)).isEqualTo("hello");
        }

        @Test
        @DisplayName("Empty string — coercion failure, returns empty string unchanged")
        void emptyStringCoercionFailure() {
            assertThat(coercer.coerce("", SchemaFieldType.NUMBER)).isEqualTo("");
        }
    }

    // -------------------------------------------------------------------------
    // BOOLEAN coercion
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("BOOLEAN schema type")
    class BooleanCoercion {

        @Test
        @DisplayName("Boolean value — no-op")
        void booleanNoOp() {
            assertThat(coercer.coerce(true, SchemaFieldType.BOOLEAN)).isEqualTo(true);
            assertThat(coercer.coerce(false, SchemaFieldType.BOOLEAN)).isEqualTo(false);
        }

        @Test
        @DisplayName("Long non-zero → true")
        void longNonZeroToTrue() {
            assertThat(coercer.coerce(1L, SchemaFieldType.BOOLEAN)).isEqualTo(true);
            assertThat(coercer.coerce(42L, SchemaFieldType.BOOLEAN)).isEqualTo(true);
            assertThat(coercer.coerce(-1L, SchemaFieldType.BOOLEAN)).isEqualTo(true);
        }

        @Test
        @DisplayName("Long zero → false")
        void longZeroToFalse() {
            assertThat(coercer.coerce(0L, SchemaFieldType.BOOLEAN)).isEqualTo(false);
        }

        @Test
        @DisplayName("Integer non-zero → true")
        void integerNonZeroToTrue() {
            assertThat(coercer.coerce(1, SchemaFieldType.BOOLEAN)).isEqualTo(true);
        }

        @Test
        @DisplayName("Integer zero → false")
        void integerZeroToFalse() {
            assertThat(coercer.coerce(0, SchemaFieldType.BOOLEAN)).isEqualTo(false);
        }

        @Test
        @DisplayName("String \"true\" → true")
        void stringTrueToBoolean() {
            assertThat(coercer.coerce("true", SchemaFieldType.BOOLEAN)).isEqualTo(true);
            assertThat(coercer.coerce("TRUE", SchemaFieldType.BOOLEAN)).isEqualTo(true);
        }

        @Test
        @DisplayName("String \"false\" → false")
        void stringFalseToBoolean() {
            assertThat(coercer.coerce("false", SchemaFieldType.BOOLEAN)).isEqualTo(false);
            assertThat(coercer.coerce("FALSE", SchemaFieldType.BOOLEAN)).isEqualTo(false);
        }

        @Test
        @DisplayName("Double — coercion failure, returns Double unchanged")
        void doubleCoercionFailure() {
            Object result = coercer.coerce(1.0, SchemaFieldType.BOOLEAN);
            assertThat(result).isEqualTo(1.0);
            assertThat(result).isInstanceOf(Double.class);
        }

        @Test
        @DisplayName("String non-boolean — coercion failure, returns String unchanged")
        void stringNonBooleanCoercionFailure() {
            assertThat(coercer.coerce("hello", SchemaFieldType.BOOLEAN)).isEqualTo("hello");
        }

        @Test
        @DisplayName("Empty string — coercion failure, returns empty string unchanged")
        void emptyStringCoercionFailure() {
            assertThat(coercer.coerce("", SchemaFieldType.BOOLEAN)).isEqualTo("");
        }
    }

    // -------------------------------------------------------------------------
    // FILE coercion
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("FILE schema type")
    class FileCoercion {

        @Test
        @DisplayName("String value — no-op")
        void stringNoOp() {
            assertThat(coercer.coerce("@ef/suites/abc/file.txt", SchemaFieldType.FILE))
                    .isEqualTo("@ef/suites/abc/file.txt");
        }

        @Test
        @DisplayName("Long → String")
        void longToString() {
            assertThat(coercer.coerce(42L, SchemaFieldType.FILE)).isEqualTo("42");
        }

        @Test
        @DisplayName("Boolean → String")
        void booleanToString() {
            assertThat(coercer.coerce(true, SchemaFieldType.FILE)).isEqualTo("true");
        }
    }

    // -------------------------------------------------------------------------
    // Null / passthrough cases
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Null and passthrough")
    class NullAndPassthrough {

        @Test
        @DisplayName("Null schema type — value returned as-is")
        void nullSchemaTypePassthrough() {
            assertThat(coercer.coerce(42L, null)).isEqualTo(42L);
            assertThat(coercer.coerce("hello", null)).isEqualTo("hello");
            assertThat(coercer.coerce(true, null)).isEqualTo(true);
        }

        @Test
        @DisplayName("Null value — returned as-is")
        void nullValuePassthrough() {
            assertThat(coercer.coerce(null, SchemaFieldType.STRING)).isNull();
            assertThat(coercer.coerce(null, SchemaFieldType.INTEGER)).isNull();
        }

        @Test
        @DisplayName("OBJECT schema type — value returned as-is")
        void objectPassthrough() {
            Object value = java.util.Map.of("key", "value");
            assertThat(coercer.coerce(value, SchemaFieldType.OBJECT)).isSameAs(value);
        }

        @Test
        @DisplayName("ARRAY schema type — value returned as-is")
        void arrayPassthrough() {
            Object value = java.util.List.of("a", "b");
            assertThat(coercer.coerce(value, SchemaFieldType.ARRAY)).isSameAs(value);
        }

        // Tripwire for change `enforce-response-column-types`. The response-column reconciler
        // (ResponseColumnTypeReconciler) wraps ARRAY+scalar into a singleton list. CSV import
        // does NOT mirror that policy today — scalar cells declared as ARRAY remain scalars.
        // If this assumption ever changes, this test fails and forces an explicit decision
        // on whether the parallel CSV bug should be fixed in lockstep.
        @Test
        @DisplayName(
                "ARRAY schema type + scalar cell — currently passes through unchanged (tripwire for #883 follow-up)")
        void shouldDocumentArrayCellHandling() {
            Object scalar = "single-value.pdf";
            assertThat(coercer.coerce(scalar, SchemaFieldType.ARRAY)).isSameAs(scalar);
        }
    }
}
