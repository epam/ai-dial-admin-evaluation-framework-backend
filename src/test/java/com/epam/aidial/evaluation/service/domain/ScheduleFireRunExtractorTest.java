package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("ScheduleFireRunExtractor")
class ScheduleFireRunExtractorTest {

    private static final String RUN_ID = "3f0c6d3e-6a43-4a4f-9f0e-0c2b0d6a0001";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduleFireRunExtractor extractor = new ScheduleFireRunExtractor();

    @Test
    @DisplayName("returns the run id from the recorded 202 response of a real fire")
    void extractsRunIdFromRecordedSample() {
        final JsonNode result = json("""
                {"body":{"id":"%s","testSuiteId":"11111111-1111-1111-1111-111111111111","status":"PENDING"},\
                "status_code":202}
                """.formatted(RUN_ID));

        assertThat(extractor.extractRunId(result)).contains(UUID.fromString(RUN_ID));
    }

    @Test
    @DisplayName("returns nothing for a 429 body, even when it carries an id")
    void ignoresErrorStatus() {
        final JsonNode result = json("{\"body\":{\"id\":\"%s\"},\"status_code\":429}".formatted(RUN_ID));

        assertThat(extractor.extractRunId(result)).isEmpty();
    }

    @Test
    @DisplayName("returns nothing for a null result")
    void ignoresNullResult() {
        assertThat(extractor.extractRunId(null)).isEmpty();
        assertThat(extractor.extractRunId(json("null"))).isEmpty();
    }

    @Test
    @DisplayName("returns nothing when the id is not a UUID")
    void ignoresNonUuidId() {
        final JsonNode result = json("{\"body\":{\"id\":\"not-a-uuid\"},\"status_code\":202}");

        assertThat(extractor.extractRunId(result)).isEmpty();
    }

    @Test
    @DisplayName("returns nothing when the body or its id is missing")
    void ignoresMissingBody() {
        assertThat(extractor.extractRunId(json("{\"status_code\":202}"))).isEmpty();
        assertThat(extractor.extractRunId(json("{\"body\":{},\"status_code\":202}")))
                .isEmpty();
        assertThat(extractor.extractRunId(json("{\"body\":{\"id\":42},\"status_code\":202}")))
                .isEmpty();
    }

    @Test
    @DisplayName("returns nothing when the status code is missing or not a number")
    void ignoresMissingStatusCode() {
        assertThat(extractor.extractRunId(json("{\"body\":{\"id\":\"%s\"}}".formatted(RUN_ID))))
                .isEmpty();
        assertThat(extractor.extractRunId(json("{\"body\":{\"id\":\"%s\"},\"status_code\":\"202\"}".formatted(RUN_ID))))
                .isEmpty();
    }

    private JsonNode json(String text) {
        return objectMapper.readTree(text);
    }
}
