package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OutputSchemaFieldExtractor")
class OutputSchemaFieldExtractorTest {

    private OutputSchemaFieldExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new OutputSchemaFieldExtractor(new ObjectMapper());
    }

    @Test
    @DisplayName("Valid schema with single field returns that field name")
    void validSingleField_returnsFieldName() {
        String schema = """
                {"properties": {"exact_match": {"type": "number"}}}
                """;

        List<String> result = extractor.extractFieldNames(schema);

        assertThat(result).containsExactly("exact_match");
    }

    @Test
    @DisplayName("Valid schema with multiple fields returns all field names")
    void validMultipleFields_returnsAllFieldNames() {
        String schema = """
                {"properties": {"recall": {}, "precision": {}, "f1": {}}}
                """;

        List<String> result = extractor.extractFieldNames(schema);

        assertThat(result).containsExactlyInAnyOrder("recall", "precision", "f1");
    }

    @Test
    @DisplayName("Null input returns empty list")
    void nullInput_returnsEmptyList() {
        List<String> result = extractor.extractFieldNames(null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Blank string returns empty list")
    void blankInput_returnsEmptyList() {
        List<String> result = extractor.extractFieldNames("   ");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Empty JSON object returns empty list")
    void emptyJsonObject_returnsEmptyList() {
        List<String> result = extractor.extractFieldNames("{}");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Schema without 'properties' key returns empty list")
    void schemaWithoutProperties_returnsEmptyList() {
        String schema = """
                {"type": "object", "required": ["score"]}
                """;

        List<String> result = extractor.extractFieldNames(schema);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Schema where 'properties' is not an object returns empty list")
    void propertiesNotObject_returnsEmptyList() {
        String schema = """
                {"properties": ["score", "recall"]}
                """;

        List<String> result = extractor.extractFieldNames(schema);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Malformed JSON returns empty list")
    void malformedJson_returnsEmptyList() {
        List<String> result = extractor.extractFieldNames("{not valid json");

        assertThat(result).isEmpty();
    }
}
