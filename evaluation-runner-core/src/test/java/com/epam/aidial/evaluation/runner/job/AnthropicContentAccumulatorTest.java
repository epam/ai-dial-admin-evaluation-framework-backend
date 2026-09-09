package com.epam.aidial.evaluation.runner.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

@DisplayName("AnthropicContentAccumulator")
class AnthropicContentAccumulatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AnthropicContentAccumulator accumulator = new AnthropicContentAccumulator(OBJECT_MAPPER);

    @Test
    @DisplayName("Should return an empty content array when no block was started")
    void buildContentArray_noBlocksStarted_returnsEmptyArray() {
        assertThat(accumulator.buildContentArray()).isEmpty();
    }

    @Test
    @DisplayName("Should ignore null and non-object content_block payloads on block start")
    void onBlockStart_nullOrNonObject_noOp() {
        accumulator.onBlockStart(0, null);
        accumulator.onBlockStart(0, node("\"not-an-object\""));

        assertThat(accumulator.buildContentArray()).isEmpty();
    }

    @Test
    @DisplayName("Should ignore a delta payload that is null")
    void onBlockDelta_null_noOp() {
        accumulator.onBlockStart(0, node("{\"type\":\"text\",\"text\":\"\"}"));
        accumulator.onBlockDelta(0, null);

        assertThat(accumulator.buildContentArray().get(0).get("text").asString())
                .isEqualTo("");
    }

    @Nested
    @DisplayName("Text blocks")
    class TextBlocks {

        @Test
        @DisplayName("Should concatenate text_delta chunks across multiple content_block_delta events")
        void buildContentArray_textBlockAcrossMultipleDeltas_concatenatesText() {
            accumulator.onBlockStart(0, node("{\"type\":\"text\",\"text\":\"\"}"));
            accumulator.onBlockDelta(0, node("{\"type\":\"text_delta\",\"text\":\"Hello, \"}"));
            accumulator.onBlockDelta(0, node("{\"type\":\"text_delta\",\"text\":\"world!\"}"));

            ArrayNode content = accumulator.buildContentArray();
            assertThat(content).hasSize(1);
            assertThat(content.get(0).get("text").asString()).isEqualTo("Hello, world!");
        }
    }

    @Nested
    @DisplayName("Tool use blocks")
    class ToolUseBlocks {

        @Test
        @DisplayName("Should reassemble input_json_delta chunks into parsed JSON input")
        void buildContentArray_toolUseBlockWithValidJson_parsesInputJson() {
            accumulator.onBlockStart(0, node("{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\"}"));
            accumulator.onBlockDelta(
                    0, node("{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"location\\\":\"}"));
            accumulator.onBlockDelta(0, node("{\"type\":\"input_json_delta\",\"partial_json\":\"\\\"NYC\\\"}\"}"));

            JsonNode block = accumulator.buildContentArray().get(0);
            assertThat(block.get("input").get("location").asString()).isEqualTo("NYC");
        }

        @Test
        @DisplayName("Should fall back to the raw accumulated string when the reassembled JSON fails to parse")
        void buildContentArray_toolUseBlockWithInvalidJson_fallsBackToRawString() {
            accumulator.onBlockStart(0, node("{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_weather\"}"));
            accumulator.onBlockDelta(0, node("{\"type\":\"input_json_delta\",\"partial_json\":\"not-json\"}"));

            JsonNode block = accumulator.buildContentArray().get(0);
            assertThat(block.get("input").asString()).isEqualTo("not-json");
        }

        @Test
        @DisplayName("Should set an empty object input when the accumulated JSON delta is an empty string")
        void buildContentArray_toolUseBlockWithEmptyDelta_setsEmptyObjectInput() {
            accumulator.onBlockStart(0, node("{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"noop\"}"));
            accumulator.onBlockDelta(0, node("{\"type\":\"input_json_delta\",\"partial_json\":\"\"}"));

            JsonNode block = accumulator.buildContentArray().get(0);
            assertThat(block.get("input").isObject()).isTrue();
            assertThat(block.get("input").size()).isZero();
        }
    }

    @Nested
    @DisplayName("Other block types")
    class OtherBlockTypes {

        @Test
        @DisplayName("Should pass through a non-text/tool_use block unchanged, ignoring any delta sent for it")
        void buildContentArray_otherBlockType_passedThroughUnchangedIgnoringDeltas() {
            accumulator.onBlockStart(0, node("{\"type\":\"thinking\",\"thinking\":\"reasoning...\"}"));
            accumulator.onBlockDelta(0, node("{\"type\":\"thinking_delta\",\"thinking\":\" more\"}"));

            JsonNode block = accumulator.buildContentArray().get(0);
            assertThat(block.get("thinking").asString()).isEqualTo("reasoning...");
        }
    }

    @Nested
    @DisplayName("Ordering")
    class Ordering {

        @Test
        @DisplayName("Should output content blocks in ascending index order regardless of start order")
        void buildContentArray_blocksStartedOutOfOrder_outputAscendingByIndex() {
            accumulator.onBlockStart(1, node("{\"type\":\"text\",\"text\":\"\"}"));
            accumulator.onBlockDelta(1, node("{\"type\":\"text_delta\",\"text\":\"Second\"}"));
            accumulator.onBlockStart(0, node("{\"type\":\"text\",\"text\":\"\"}"));
            accumulator.onBlockDelta(0, node("{\"type\":\"text_delta\",\"text\":\"First\"}"));

            ArrayNode content = accumulator.buildContentArray();
            assertThat(content.get(0).get("text").asString()).isEqualTo("First");
            assertThat(content.get(1).get("text").asString()).isEqualTo("Second");
        }
    }

    @Nested
    @DisplayName("accumulatedText")
    class AccumulatedText {

        @Test
        @DisplayName("Should return an empty string when no block was started")
        void accumulatedText_noBlocksStarted_returnsEmptyString() {
            assertThat(accumulator.accumulatedText()).isEmpty();
        }

        @Test
        @DisplayName("Should concatenate only text blocks, in ascending index order, excluding tool_use blocks")
        void accumulatedText_mixedBlockTypes_concatenatesOnlyTextBlocksInIndexOrder() {
            accumulator.onBlockStart(0, node("{\"type\":\"text\",\"text\":\"\"}"));
            accumulator.onBlockDelta(0, node("{\"type\":\"text_delta\",\"text\":\"Hello \"}"));
            accumulator.onBlockStart(1, node("{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"noop\"}"));
            accumulator.onBlockDelta(1, node("{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}"));
            accumulator.onBlockStart(2, node("{\"type\":\"text\",\"text\":\"\"}"));
            accumulator.onBlockDelta(2, node("{\"type\":\"text_delta\",\"text\":\"world\"}"));

            assertThat(accumulator.accumulatedText()).isEqualTo("Hello world");
        }
    }

    private JsonNode node(String json) {
        return OBJECT_MAPPER.readTree(json);
    }
}
