package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ExtractedColumnsNormalizer shape detection")
class ExtractedColumnsNormalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExtractedColumnsNormalizer normalizer = new ExtractedColumnsNormalizer(objectMapper);

    @Test
    @DisplayName("array reduces to its last element")
    void arrayReducesToLastElement() {
        JsonNode node = objectMapper.readTree("[{\"score\":1},{\"score\":2},{\"score\":3}]");

        JsonNode result = normalizer.normalize(node);

        assertThat(result.isObject()).isTrue();
        assertThat(result.get("score").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("length-1 array reduces to its single element")
    void lengthOneArrayReducesToElement() {
        JsonNode node = objectMapper.readTree("[{\"score\":42}]");

        JsonNode result = normalizer.normalize(node);

        assertThat(result.isObject()).isTrue();
        assertThat(result.get("score").asInt()).isEqualTo(42);
    }

    @Test
    @DisplayName("empty array normalizes to an empty object")
    void emptyArrayNormalizesToEmptyObject() {
        JsonNode node = objectMapper.readTree("[]");

        JsonNode result = normalizer.normalize(node);

        assertThat(result.isObject()).isTrue();
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("object is returned unchanged")
    void objectReturnedUnchanged() {
        JsonNode node = objectMapper.readTree("{\"score\":7}");

        JsonNode result = normalizer.normalize(node);

        assertThat(result.get("score").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("null node normalizes to an empty object")
    void nullNormalizesToEmptyObject() {
        JsonNode result = normalizer.normalize(null);

        assertThat(result.isObject()).isTrue();
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("normalizeToJsonString reduces an array string to the last object string")
    void normalizeToJsonStringReducesArray() {
        String result = normalizer.normalizeToJsonString("[{\"score\":1},{\"score\":2}]");

        assertThat(objectMapper.readTree(result).get("score").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("normalizeToJsonString turns an empty array string into an empty object")
    void normalizeToJsonStringEmptyArray() {
        assertThat(normalizer.normalizeToJsonString("[]")).isEqualTo("{}");
    }

    @Test
    @DisplayName("normalizeToJsonString passes an object string through unchanged")
    void normalizeToJsonStringObjectUnchanged() {
        String result = normalizer.normalizeToJsonString("{\"score\":5}");

        assertThat(objectMapper.readTree(result).get("score").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("normalizeToJsonString passes null/blank through unchanged")
    void normalizeToJsonStringNullBlank() {
        assertThat(normalizer.normalizeToJsonString(null)).isNull();
        assertThat(normalizer.normalizeToJsonString("  ")).isEqualTo("  ");
    }
}
