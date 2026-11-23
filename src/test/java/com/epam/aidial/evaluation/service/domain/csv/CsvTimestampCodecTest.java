package com.epam.aidial.evaluation.service.domain.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CsvTimestampCodecTest {
    private final CsvTimestampCodec codec = new CsvTimestampCodec();

    @ParameterizedTest
    @MethodSource("timestamps")
    @DisplayName("formats and parses epoch values with exactly three UTC fractional digits")
    void formatsAndParses(Long millis, String iso) {
        assertThat(codec.format(millis)).isEqualTo(iso);
        assertThat(codec.parse(iso)).isEqualTo(millis);
        assertThat(codec.parse(millis.toString())).isEqualTo(millis);
    }

    private static Stream<Arguments> timestamps() {
        return Stream.of(
                Arguments.of(0L, "1970-01-01T00:00:00.000Z"),
                Arguments.of(-1L, "1969-12-31T23:59:59.999Z"),
                Arguments.of(1779704532226L, "2026-05-25T10:22:12.226Z"),
                Arguments.of(Long.MIN_VALUE, "-292275055-05-16T16:47:04.192Z"),
                Arguments.of(Long.MAX_VALUE, "+292278994-08-17T07:12:55.807Z"));
    }

    @Test
    @DisplayName("null timestamp formats as an empty CSV cell")
    void nullFormatsEmpty() {
        assertThat(codec.format(null)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("null and blank timestamps parse as null")
    void blankParsesNull(String raw) {
        assertThat(codec.parse(raw)).isNull();
    }

    @ParameterizedTest
    @MethodSource("acceptedValues")
    @DisplayName("parses trimmed legacy integers and zero to three fractional digits exactly")
    void parsesAcceptedValues(String raw, long expected) {
        assertThat(codec.parse(raw)).isEqualTo(expected);
    }

    private static Stream<Arguments> acceptedValues() {
        return Stream.of(
                Arguments.of("  +123  ", 123L),
                Arguments.of(" -1 ", -1L),
                Arguments.of("1970-01-01T00:00:00Z", 0L),
                Arguments.of("1970-01-01T00:00:00.1Z", 100L),
                Arguments.of("1970-01-01T00:00:00.12Z", 120L),
                Arguments.of(" 1969-12-31T23:59:59.999Z ", -1L));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "garbage",
                "1970-01-01",
                "2023-02-29T00:00:00Z",
                "2024-13-01T00:00:00Z",
                "2016-12-31T23:59:60Z",
                "1970-01-01T24:00:00Z",
                "1970-01-01T00:00:00.Z",
                "1970-01-01T00:00:00.0000Z",
                "1970-01-01T00:00:00",
                "1970-01-01T00:00:00z",
                "1970-01-01T00:00:00+00:00",
                "1970-01-01T00:00:00+01:00",
                "9223372036854775808",
                "-9223372036854775809",
                "+292278994-08-17T07:12:55.808Z",
                "-292275055-05-16T16:47:04.191Z"
            })
    @DisplayName("rejects malformed, non-UTC, overprecise, invalid, and out-of-range timestamps")
    void rejectsInvalid(String raw) {
        assertThatThrownBy(() -> codec.parse(raw)).isInstanceOf(IllegalArgumentException.class);
    }
}
