package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ArrayBindingTypeMismatchDetector")
class ArrayBindingTypeMismatchDetectorTest {

    private ArrayBindingTypeMismatchDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ArrayBindingTypeMismatchDetector(new ObjectMapper());
    }

    @Test
    @DisplayName("Flags a scalar-typed property whose resolved value is an array")
    void flagsScalarPropertyBoundToArray() {
        String schema = """
                {"properties": {"actual": {"type": "string"}}}
                """;

        List<String> result = detector.detect(schema, Map.of("actual", List.of("Paris", "Tokio")));

        assertThat(result).containsExactly("actual");
    }

    @Test
    @DisplayName("Does not flag an array-typed property whose resolved value is an array")
    void doesNotFlagArrayTypedProperty() {
        String schema = """
                {"properties": {"answers": {"type": "array"}}}
                """;

        List<String> result = detector.detect(schema, Map.of("answers", List.of("Paris", "Tokio")));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Does not flag a scalar-typed property whose resolved value is a scalar")
    void doesNotFlagScalarValue() {
        String schema = """
                {"properties": {"actual": {"type": "string"}}}
                """;

        List<String> result = detector.detect(schema, Map.of("actual", "Paris"));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Does not flag a property with no declared type (cannot judge)")
    void doesNotFlagPropertyWithoutDeclaredType() {
        String schema = """
                {"properties": {"actual": {"description": "no type here"}}}
                """;

        List<String> result = detector.detect(schema, Map.of("actual", List.of("Paris", "Tokio")));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Does not flag a property absent from the schema")
    void doesNotFlagPropertyAbsentFromSchema() {
        String schema = """
                {"properties": {"other": {"type": "string"}}}
                """;

        List<String> result = detector.detect(schema, Map.of("actual", List.of("Paris", "Tokio")));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Flags when a union type list does not permit arrays")
    void flagsUnionTypeWithoutArray() {
        String schema = """
                {"properties": {"actual": {"type": ["string", "null"]}}}
                """;

        List<String> result = detector.detect(schema, Map.of("actual", List.of("Paris", "Tokio")));

        assertThat(result).containsExactly("actual");
    }

    @Test
    @DisplayName("Does not flag when a union type list permits arrays")
    void doesNotFlagUnionTypeWithArray() {
        String schema = """
                {"properties": {"actual": {"type": ["array", "null"]}}}
                """;

        List<String> result = detector.detect(schema, Map.of("actual", List.of("Paris", "Tokio")));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns an empty list for a null or blank schema")
    void emptyForNullOrBlankSchema() {
        assertThat(detector.detect(null, Map.of("actual", List.of("Paris")))).isEmpty();
        assertThat(detector.detect("", Map.of("actual", List.of("Paris")))).isEmpty();
    }

    @Test
    @DisplayName("Returns an empty list for a malformed schema (graceful degradation)")
    void emptyForMalformedSchema() {
        List<String> result = detector.detect("{not valid json", Map.of("actual", List.of("Paris")));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Flags only the mismatched properties among many")
    void flagsOnlyMismatchedProperties() {
        String schema = """
                {"properties": {
                    "actual": {"type": "string"},
                    "answers": {"type": "array"},
                    "threshold": {"type": "number"}
                }}
                """;

        List<String> result = detector.detect(
                schema,
                Map.of(
                        "actual", List.of("Paris", "Tokio"),
                        "answers", List.of("Paris", "Tokio"),
                        "threshold", 0.8));

        assertThat(result).containsExactly("actual");
    }

    @Test
    @DisplayName("Ignores null resolved values")
    void ignoresNullValues() {
        String schema = """
                {"properties": {"actual": {"type": "string"}}}
                """;

        java.util.Map<String, Object> values = new java.util.HashMap<>();
        values.put("actual", null);

        assertThat(detector.detect(schema, values)).isEmpty();
    }
}
