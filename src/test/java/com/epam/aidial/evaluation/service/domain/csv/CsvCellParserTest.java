package com.epam.aidial.evaluation.service.domain.csv;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CsvCellParser")
class CsvCellParserTest {

    private final CsvCellParser parser = new CsvCellParser();

    // -------------------------------------------------------------------------
    // parseCell
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("parseCell")
    class ParseCell {

        @Test
        @DisplayName("\"1\" is parsed as Long(1), not Boolean(true)")
        void parsesOneAsLong() {
            Object result = parser.parseCell("1");
            assertThat(result).isEqualTo(1L);
            assertThat(result).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("\"0\" is parsed as Long(0), not Boolean(false)")
        void parsesZeroAsLong() {
            Object result = parser.parseCell("0");
            assertThat(result).isEqualTo(0L);
            assertThat(result).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("\"42\" is parsed as Long(42)")
        void parsesIntegerAsLong() {
            Object result = parser.parseCell("42");
            assertThat(result).isEqualTo(42L);
            assertThat(result).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("\"3000000000\" (exceeds Integer.MAX_VALUE) is parsed as Long(3000000000)")
        void parsesLargeIntegerAsLong() {
            Object result = parser.parseCell("3000000000");
            assertThat(result).isEqualTo(3_000_000_000L);
            assertThat(result).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("\"-42\" is parsed as Long(-42)")
        void parsesNegativeIntegerAsLong() {
            Object result = parser.parseCell("-42");
            assertThat(result).isEqualTo(-42L);
            assertThat(result).isInstanceOf(Long.class);
        }

        @Test
        @DisplayName("\"true\" is parsed as Boolean(true)")
        void parsesTrueAsBoolean() {
            Object result = parser.parseCell("true");
            assertThat(result).isEqualTo(true);
        }

        @Test
        @DisplayName("\"false\" is parsed as Boolean(false)")
        void parsesFalseAsBoolean() {
            Object result = parser.parseCell("false");
            assertThat(result).isEqualTo(false);
        }

        @Test
        @DisplayName("\"TRUE\" (case-insensitive) is parsed as Boolean(true)")
        void parsesTrueUpperCaseAsBoolean() {
            Object result = parser.parseCell("TRUE");
            assertThat(result).isEqualTo(true);
        }

        @Test
        @DisplayName("\"False\" (mixed case) is parsed as Boolean(false)")
        void parsesFalseMixedCaseAsBoolean() {
            Object result = parser.parseCell("False");
            assertThat(result).isEqualTo(false);
        }

        @Test
        @DisplayName("\"3.14\" is parsed as Double(3.14)")
        void parsesDecimalAsDouble() {
            Object result = parser.parseCell("3.14");
            assertThat(result).isEqualTo(3.14);
            assertThat(result).isInstanceOf(Double.class);
        }

        @Test
        @DisplayName("\"hello\" is parsed as String")
        void parsesTextAsString() {
            Object result = parser.parseCell("hello");
            assertThat(result).isEqualTo("hello");
        }

        @Test
        @DisplayName("null input returns empty string")
        void parsesNullAsEmptyString() {
            Object result = parser.parseCell(null);
            assertThat(result).isEqualTo("");
        }

        @Test
        @DisplayName("blank input returns empty string")
        void parsesBlankAsEmptyString() {
            Object result = parser.parseCell("   ");
            assertThat(result).isEqualTo("");
        }
    }

    // -------------------------------------------------------------------------
    // inferTypeName
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("inferTypeName")
    class InferTypeName {

        @Test
        @DisplayName("\"1\" infers as INTEGER (not BOOLEAN)")
        void infersOneAsInteger() {
            assertThat(parser.inferTypeName("1")).isEqualTo("INTEGER");
        }

        @Test
        @DisplayName("\"0\" infers as INTEGER (not BOOLEAN)")
        void infersZeroAsInteger() {
            assertThat(parser.inferTypeName("0")).isEqualTo("INTEGER");
        }

        @Test
        @DisplayName("\"true\" infers as BOOLEAN")
        void infersTrueAsBoolean() {
            assertThat(parser.inferTypeName("true")).isEqualTo("BOOLEAN");
        }

        @Test
        @DisplayName("\"false\" infers as BOOLEAN")
        void infersFalseAsBoolean() {
            assertThat(parser.inferTypeName("false")).isEqualTo("BOOLEAN");
        }

        @Test
        @DisplayName("\"42\" infers as INTEGER")
        void infersIntegerAsInteger() {
            assertThat(parser.inferTypeName("42")).isEqualTo("INTEGER");
        }

        @Test
        @DisplayName("\"3.14\" infers as NUMBER")
        void infersDecimalAsNumber() {
            assertThat(parser.inferTypeName("3.14")).isEqualTo("NUMBER");
        }

        @Test
        @DisplayName("\"hello\" infers as STRING")
        void infersTextAsString() {
            assertThat(parser.inferTypeName("hello")).isEqualTo("STRING");
        }

        @Test
        @DisplayName("null infers as STRING")
        void infersNullAsString() {
            assertThat(parser.inferTypeName(null)).isEqualTo("STRING");
        }

        @Test
        @DisplayName("blank infers as STRING")
        void infersBlankAsString() {
            assertThat(parser.inferTypeName("  ")).isEqualTo("STRING");
        }
    }
}
