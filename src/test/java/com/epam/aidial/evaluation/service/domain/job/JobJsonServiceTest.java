package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("JsonService")
class JobJsonServiceTest {

    private final JobJsonService jsonService = new JobJsonService(new ObjectMapper());

    @Nested
    @DisplayName("writeOrToString")
    class WriteOrToString {
        @Test
        @DisplayName("null value serializes to null")
        void nullValue() {
            assertThat(jsonService.writeOrToString(null)).isNull();
        }

        @Test
        @DisplayName("serializes a value to JSON")
        void serializes() {
            assertThat(jsonService.writeOrToString(Map.of("a", 1))).isEqualTo("{\"a\":1}");
        }

        @Test
        @DisplayName("falls back to toString() when serialization fails")
        void fallbackToToString() {
            final Object unserializable = new Exploding();

            assertThat(jsonService.writeOrToString(unserializable)).isEqualTo(unserializable.toString());
        }
    }

    @Nested
    @DisplayName("readMapOrEmpty")
    class ReadMapOrEmpty {
        @Test
        @DisplayName("null input yields an empty map")
        void nullInput() {
            assertThat(jsonService.readMapOrEmpty(null)).isEmpty();
        }

        @Test
        @DisplayName("blank input yields an empty map")
        void blankInput() {
            assertThat(jsonService.readMapOrEmpty("   ")).isEmpty();
        }

        @Test
        @DisplayName("parses a JSON object into a map")
        void parses() {
            final Map<String, Object> map = jsonService.readMapOrEmpty("{\"a\":1,\"b\":\"x\"}");

            assertThat(map).containsEntry("b", "x").containsKey("a");
        }

        @Test
        @DisplayName("malformed JSON yields an empty map")
        void malformed() {
            assertThat(jsonService.readMapOrEmpty("not-json")).isEmpty();
        }
    }

    @Nested
    @DisplayName("readTreeOrEmpty")
    class ReadTreeOrEmpty {
        @Test
        @DisplayName("parses a JSON tree")
        void parses() {
            final JsonNode tree = jsonService.readTreeOrEmpty("{\"a\":1}");

            assertThat(tree.path("a").asInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("blank input yields an empty object node")
        void blankInput() {
            final JsonNode tree = jsonService.readTreeOrEmpty("  ");

            assertThat(tree.isObject()).isTrue();
            assertThat(tree.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("malformed JSON yields an empty object node navigable with path()")
        void malformed() {
            final JsonNode tree = jsonService.readTreeOrEmpty("not-json");

            assertThat(tree.isObject()).isTrue();
            assertThat(tree.path("choices").path(0).path("message").isObject()).isFalse();
        }
    }

    @Nested
    @DisplayName("node factories")
    class NodeFactories {
        @Test
        @DisplayName("createObjectNode returns an empty mutable object node")
        void objectNode() {
            assertThat(jsonService.createObjectNode().isObject()).isTrue();
        }

        @Test
        @DisplayName("createArrayNode returns an empty mutable array node")
        void arrayNode() {
            assertThat(jsonService.createArrayNode().isArray()).isTrue();
        }
    }

    /** A bean whose getter throws during serialization, forcing Jackson to raise a JacksonException. */
    static class Exploding {
        public String getValue() {
            throw new IllegalStateException("boom");
        }
    }
}
