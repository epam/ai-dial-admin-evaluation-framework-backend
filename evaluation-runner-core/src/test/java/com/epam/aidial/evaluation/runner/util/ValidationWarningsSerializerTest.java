package com.epam.aidial.evaluation.runner.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract tests for the lenient {@link ValidationWarningsSerializer#deserializeTurns} and the strict
 * sibling {@link ValidationWarningsSerializer#deserializeTurnsStrict} — the two must agree on "absent"
 * (null/blank input) but diverge on "unreadable" (non-blank input that fails to parse): the lenient
 * method degrades to {@code null}, the strict method propagates the {@link JacksonException}.
 */
class ValidationWarningsSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ValidationWarningsSerializer serializer = new ValidationWarningsSerializer(objectMapper);

    private static final String VALID_TURNS_JSON = "[{\"prompt\":\"a\"},{\"prompt\":\"b\"}]";
    private static final String UNREADABLE_JSON = "{not valid json";

    @Test
    @DisplayName("deserializeTurns: null input returns null")
    void lenientNullReturnsNull() {
        assertThat(serializer.deserializeTurns(null)).isNull();
    }

    @Test
    @DisplayName("deserializeTurns: blank input returns null")
    void lenientBlankReturnsNull() {
        assertThat(serializer.deserializeTurns("   ")).isNull();
    }

    @Test
    @DisplayName("deserializeTurns: valid JSON parses to the turn list")
    void lenientValidJsonParses() {
        List<Map<String, Object>> turns = serializer.deserializeTurns(VALID_TURNS_JSON);

        assertThat(turns).containsExactly(Map.of("prompt", "a"), Map.of("prompt", "b"));
    }

    @Test
    @DisplayName("deserializeTurns: unreadable JSON degrades gracefully to null")
    void lenientUnreadableReturnsNull() {
        assertThat(serializer.deserializeTurns(UNREADABLE_JSON)).isNull();
    }

    @Test
    @DisplayName("deserializeTurnsStrict: null input returns null")
    void strictNullReturnsNull() {
        assertThat(serializer.deserializeTurnsStrict(null)).isNull();
    }

    @Test
    @DisplayName("deserializeTurnsStrict: blank input returns null")
    void strictBlankReturnsNull() {
        assertThat(serializer.deserializeTurnsStrict("   ")).isNull();
    }

    @Test
    @DisplayName("deserializeTurnsStrict: valid JSON parses to the turn list")
    void strictValidJsonParses() {
        List<Map<String, Object>> turns = serializer.deserializeTurnsStrict(VALID_TURNS_JSON);

        assertThat(turns).containsExactly(Map.of("prompt", "a"), Map.of("prompt", "b"));
    }

    @Test
    @DisplayName("deserializeTurnsStrict: unreadable JSON propagates JacksonException rather than returning null")
    void strictUnreadableThrows() {
        assertThatThrownBy(() -> serializer.deserializeTurnsStrict(UNREADABLE_JSON))
                .isInstanceOf(JacksonException.class);
    }
}
