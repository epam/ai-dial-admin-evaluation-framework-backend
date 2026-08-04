package com.epam.aidial.evaluation.runner.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("CustomContentAccumulator")
class CustomContentAccumulatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CustomContentAccumulator accumulator = new CustomContentAccumulator();

    @Test
    @DisplayName("Should return null when nothing was accumulated")
    void getMerged_noChunksAccumulated_returnsNull() {
        assertThat(accumulator.getMerged()).isNull();
    }

    @Test
    @DisplayName("Should ignore null, JSON-null and non-object custom_content nodes")
    void accumulate_nullOrNonObject_noOp() {
        accumulator.accumulate(null);
        accumulator.accumulate(node("null"));
        accumulator.accumulate(node("\"not-an-object\""));

        assertThat(accumulator.getMerged()).isNull();
    }

    @Nested
    @DisplayName("Scalar fields")
    class ScalarFields {

        @Test
        @DisplayName("Should overwrite a scalar field with the last non-null value across chunks")
        void accumulate_scalarField_lastNonNullWins() {
            accumulator.accumulate(node("{\"state\":\"pending\"}"));
            accumulator.accumulate(node("{\"state\":\"running\"}"));
            accumulator.accumulate(node("{\"state\":null}"));

            ObjectNode merged = accumulator.getMerged();
            assertThat(merged.get("state").asString()).isEqualTo("running");
        }

        @Test
        @DisplayName("Should preserve a previously accumulated field when a later chunk omits it")
        void accumulate_laterChunkOmitsField_preservesEarlierValue() {
            accumulator.accumulate(node("{\"state\":\"running\",\"foo\":\"bar\"}"));
            accumulator.accumulate(node("{\"state\":\"done\"}"));

            ObjectNode merged = accumulator.getMerged();
            assertThat(merged.get("state").asString()).isEqualTo("done");
            assertThat(merged.get("foo").asString()).isEqualTo("bar");
        }

        @Test
        @DisplayName("Should accumulate custom_content even when the chunk carries no text delta at all")
        void accumulate_chunkWithOnlyCustomContent_stillAccumulated() {
            accumulator.accumulate(node("{\"state\":\"running\"}"));

            ObjectNode merged = accumulator.getMerged();
            assertThat(merged.get("state").asString()).isEqualTo("running");
        }
    }

    @Nested
    @DisplayName("Stages")
    class Stages {

        @Test
        @DisplayName("Should deep-merge a stage split across name/content/status partial chunks by index")
        void accumulate_stagePartialsAcrossChunks_mergedByIndex() {
            accumulator.accumulate(node("{\"stages\":[{\"index\":0,\"name\":\"Searching\"}]}"));
            accumulator.accumulate(node("{\"stages\":[{\"index\":0,\"content\":\"Looking up docs\"}]}"));
            accumulator.accumulate(node("{\"stages\":[{\"index\":0,\"status\":\"completed\"}]}"));

            ObjectNode merged = accumulator.getMerged();
            JsonNode stages = merged.get("stages");
            assertThat(stages).hasSize(1);
            JsonNode stage = stages.get(0);
            assertThat(stage.get("name").asString()).isEqualTo("Searching");
            assertThat(stage.get("content").asString()).isEqualTo("Looking up docs");
            assertThat(stage.get("status").asString()).isEqualTo("completed");
        }

        @Test
        @DisplayName("Should output stages in ascending index order regardless of receipt order")
        void accumulate_stagesOutOfOrder_outputAscendingByIndex() {
            accumulator.accumulate(node("{\"stages\":[{\"index\":1,\"name\":\"Second\"}]}"));
            accumulator.accumulate(node("{\"stages\":[{\"index\":0,\"name\":\"First\"}]}"));

            JsonNode stages = accumulator.getMerged().get("stages");
            assertThat(stages.get(0).get("name").asString()).isEqualTo("First");
            assertThat(stages.get(1).get("name").asString()).isEqualTo("Second");
        }
    }

    @Nested
    @DisplayName("Attachments")
    class Attachments {

        @Test
        @DisplayName("Should merge a single attachment split across multiple chunks")
        void accumulate_attachmentSplitAcrossChunks_mergedByIndex() {
            accumulator.accumulate(node("{\"attachments\":[{\"index\":0,\"type\":\"image/png\"}]}"));
            accumulator.accumulate(node("{\"attachments\":[{\"index\":0,\"title\":\"chart.png\"}]}"));
            accumulator.accumulate(node("{\"attachments\":[{\"index\":0,\"url\":\"files/chart.png\"}]}"));

            ObjectNode merged = accumulator.getMerged();
            JsonNode attachments = merged.get("attachments");
            assertThat(attachments).hasSize(1);
            JsonNode attachment = attachments.get(0);
            assertThat(attachment.get("type").asString()).isEqualTo("image/png");
            assertThat(attachment.get("title").asString()).isEqualTo("chart.png");
            assertThat(attachment.get("url").asString()).isEqualTo("files/chart.png");
        }

        @Test
        @DisplayName("Should merge two attachments whose partial updates arrive interleaved, independently by index")
        void accumulate_twoAttachmentsInterleaved_mergedIndependentlyByIndex() {
            accumulator.accumulate(node("{\"attachments\":[{\"index\":0,\"title\":\"first.png\"}]}"));
            accumulator.accumulate(node("{\"attachments\":[{\"index\":1,\"title\":\"second.png\"}]}"));
            accumulator.accumulate(node("{\"attachments\":[{\"index\":0,\"url\":\"files/first.png\"}]}"));
            accumulator.accumulate(node("{\"attachments\":[{\"index\":1,\"url\":\"files/second.png\"}]}"));

            ObjectNode merged = accumulator.getMerged();
            JsonNode attachments = merged.get("attachments");
            assertThat(attachments).hasSize(2);
            assertThat(attachments.get(0).get("title").asString()).isEqualTo("first.png");
            assertThat(attachments.get(0).get("url").asString()).isEqualTo("files/first.png");
            assertThat(attachments.get(1).get("title").asString()).isEqualTo("second.png");
            assertThat(attachments.get(1).get("url").asString()).isEqualTo("files/second.png");
        }

        @Test
        @DisplayName("Should append an attachment without an index after indexed attachments, never merging it")
        void accumulate_attachmentWithoutIndex_appendedNeverMerged() {
            accumulator.accumulate(node("{\"attachments\":[{\"index\":0,\"title\":\"first.png\"}]}"));
            accumulator.accumulate(node("{\"attachments\":[{\"title\":\"no-index.png\"}]}"));
            accumulator.accumulate(node("{\"attachments\":[{\"title\":\"another-no-index.png\"}]}"));

            ObjectNode merged = accumulator.getMerged();
            JsonNode attachments = merged.get("attachments");
            assertThat(attachments).hasSize(3);
            assertThat(attachments.get(0).get("title").asString()).isEqualTo("first.png");
            assertThat(attachments.get(1).get("title").asString()).isEqualTo("no-index.png");
            assertThat(attachments.get(2).get("title").asString()).isEqualTo("another-no-index.png");
        }
    }

    private JsonNode node(String json) {
        return OBJECT_MAPPER.readTree(json);
    }
}
