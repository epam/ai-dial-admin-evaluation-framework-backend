package com.epam.aidial.evaluation.runner.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("Annotation deltas in both extensions")
class AnnotationDeltaAccumulatorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Should merge narrow identities in first-seen order while preserving unkeyed entries")
    void accumulate_identitiesAndUnknownValues_preservesShape(boolean content) {
        final Fixture fixture = new Fixture(content);
        fixture.add("""
                {"annotations":[
                  {"index":0,"body":{"title":"A ","quote":"Read ","extra":{"a":1,"nil":null},"items":[{"nil":null}]}},
                  {"target":{"selector":{"type":"html_tag","id":"ref-a"}},"body":{"quote":"See "}},
                  {"id":"duplicate"},{"target":{"selector":{"type":"CssSelector","id":"ref-a"}}},null,4,
                  {"index":1,"body":{"quote":7}},
                  {"target":{"selector":{"type":"html_tag","id":"1"}},"body":{"quote":"selector"}}]}
                """);
        fixture.add("""
                {"annotations":[
                  {"index":0,"body":{"title":"A ","quote":"this.","extra":{"b":2},"items":[null,2],"newObject":{"optional":null}}},
                  {"index":-1,"target":{"selector":{"type":"html_tag","id":"ref-a"}},"body":{"quote":"this."}},
                  {"target":{"selector":{"type":"html_tag","id":"ref-b"}}},
                  {"id":"duplicate"},{"target":{"selector":{"type":"CssSelector","id":"ref-a"}}},
                  {"index":1,"body":{"quote":""}}]}
                """);
        assertThat(fixture.result()
                        .get("annotations")
                        .get(6)
                        .get("body")
                        .get("quote")
                        .asInt())
                .isEqualTo(7);
        fixture.add("""
                {"annotations":[{"index":0,"body":{"quote":null,"newObject":null}},
                  {"index":1,"body":{"quote":"text"}}]}
                """);
        fixture.add("{\"annotations\":[]}");
        assertThat(fixture.result()).isEqualTo(node("""
                {"annotations":[
                  {"index":0,"body":{"title":"A A ","quote":"Read this.","extra":{"a":1,"nil":null,"b":2},"items":[{"nil":null},null,2],"newObject":{"optional":null}}},
                  {"index":-1,"target":{"selector":{"type":"html_tag","id":"ref-a"}},"body":{"quote":"See this."}},
                  {"id":"duplicate"},{"target":{"selector":{"type":"CssSelector","id":"ref-a"}}},null,4,
                  {"index":1,"body":{"quote":"text"}},
                  {"target":{"selector":{"type":"html_tag","id":"1"}},"body":{"quote":"selector"}},
                  {"target":{"selector":{"type":"html_tag","id":"ref-b"}}},
                  {"id":"duplicate"},{"target":{"selector":{"type":"CssSelector","id":"ref-a"}}}]}
                """));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Should use valid indexes before selectors and fall back for invalid indexes")
    void accumulate_indexValidation_usesSelectorFallback(boolean content) {
        final Fixture fixture = new Fixture(content);
        fixture.add("""
                {"annotations":[
                  {"index":0,"target":{"selector":{"type":"html_tag","id":"same"}},"body":{"title":"indexed"}},
                  {"index":0.5,"target":{"selector":{"type":"html_tag","id":"same"}},"body":{"title":"select "}},
                  {"index":"0","target":{"selector":{"type":"html_tag","id":"same"}},"body":{"title":"again"}},
                  {"target":{"selector":{"type":"html_tag","id":""}}},
                  {"target":{"selector":{"type":"html_tag","id":""}}},
                  {"target":{"selector":"invalid"}},{"target":{"selector":"invalid"}}]}
                """);
        assertThat(fixture.result()).isEqualTo(node("""
                {"annotations":[
                  {"index":0,"target":{"selector":{"type":"html_tag","id":"same"}},"body":{"title":"indexed"}},
                  {"index":"0","target":{"selector":{"type":"html_tag","id":"same"}},"body":{"title":"select again"}},
                  {"target":{"selector":{"type":"html_tag","id":""}}},
                  {"target":{"selector":{"type":"html_tag","id":""}}},
                  {"target":{"selector":"invalid"}},{"target":{"selector":"invalid"}}]}
                """));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Should merge duplicate keyed entries and preserve nulls in replacement annotation objects")
    void accumulate_duplicateKeysAndReplacementObjects_preserveNulls(boolean content) {
        final Fixture fixture = new Fixture(content);
        fixture.add("""
                {"annotations":[{"index":123456789012345678901234567890,"body":{"quote":"one ","extra":"old"}},
                   {"index":123456789012345678901234567890,"body":{"quote":"two","extra":{"nil":null}}},
                   {"index":123456789012345678901234567891,"body":{"quote":"separate"}}]}
                """);
        fixture.add("""
                {"annotations":[{"index":123456789012345678901234567890,"body":{"quote":7,"extra":null}}]}
                """);
        fixture.add("""
                {"annotations":[{"index":123456789012345678901234567890,"body":{"quote":"","extra":{"new":1}}}]}
                """);
        assertThat(fixture.result()).isEqualTo(node("""
                {"annotations":[{"index":123456789012345678901234567890,"body":{"quote":7,"extra":{"nil":null,"new":1}}},
                   {"index":123456789012345678901234567891,"body":{"quote":"separate"}}]}
                """));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Should replace annotation types and isolate event and result mutations")
    void accumulate_typeTransitionsAndCopies_areIsolated(boolean content) {
        final Fixture fixture = new Fixture(content);
        final ObjectNode first = (ObjectNode) node("{\"annotations\":[{\"index\":0,\"body\":{\"quote\":\"a\"}}]}");
        final JsonNode original = first.deepCopy();
        fixture.consumer.accept(first);
        fixture.add("{\"annotations\":[{\"index\":0,\"body\":{\"quote\":\"b\"}}]}");
        assertThat(first).isEqualTo(original);
        ((ObjectNode) first.get("annotations").get(0).get("body")).put("quote", "changed");
        final ObjectNode result = fixture.result();
        ((ObjectNode) result.get("annotations").get(0).get("body")).put("quote", "changed");
        assertThat(fixture.result()).isEqualTo(node("{\"annotations\":[{\"index\":0,\"body\":{\"quote\":\"ab\"}}]}"));
        fixture.add("{\"annotations\":{\"old\":1}}");
        assertThat(fixture.result()).isEqualTo(node("{\"annotations\":{\"old\":1}}"));
        fixture.add("{\"annotations\":[{\"index\":0,\"body\":{\"quote\":\"new\"}}]}");
        assertThat(fixture.result()).isEqualTo(node("{\"annotations\":[{\"index\":0,\"body\":{\"quote\":\"new\"}}]}"));
    }

    private static JsonNode node(String json) {
        return MAPPER.readTree(json);
    }

    private static final class Fixture {
        private final Consumer<JsonNode> consumer;
        private final Supplier<ObjectNode> supplier;

        Fixture(boolean content) {
            if (content) {
                final CustomContentAccumulator accumulator = new CustomContentAccumulator();
                consumer = accumulator::accumulate;
                supplier = accumulator::getMerged;
            } else {
                final CustomFieldsAccumulator accumulator = new CustomFieldsAccumulator();
                consumer = accumulator::accumulate;
                supplier = accumulator::getMerged;
            }
        }

        void add(String json) {
            consumer.accept(node(json));
        }

        ObjectNode result() {
            return supplier.get();
        }
    }
}
