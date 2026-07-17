package com.epam.aidial.evaluation.service.domain.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("DisabledTestCaseIdsCodec")
class DisabledTestCaseIdsCodecTest {

    private final DisabledTestCaseIdsCodec codec = new DisabledTestCaseIdsCodec(new ObjectMapper());

    @Nested
    @DisplayName("deserialize")
    class Deserialize {

        @Test
        @DisplayName("returns an empty list for a null payload")
        void nullYieldsEmptyList() {
            assertThat(codec.deserialize(null)).isEmpty();
        }

        @Test
        @DisplayName("returns an empty list for a blank payload")
        void blankYieldsEmptyList() {
            assertThat(codec.deserialize("   ")).isEmpty();
        }

        @Test
        @DisplayName("parses a JSON array of stringified UUIDs into typed UUIDs")
        void parsesValidArray() {
            final UUID a = UUID.randomUUID();
            final UUID b = UUID.randomUUID();
            final String json = "[\"" + a + "\",\"" + b + "\"]";

            assertThat(codec.deserialize(json)).containsExactly(a, b);
        }

        @Test
        @DisplayName("returns an empty list for an empty JSON array")
        void parsesEmptyArray() {
            assertThat(codec.deserialize("[]")).isEmpty();
        }

        @Test
        @DisplayName("skips null and blank entries, keeping the valid UUIDs")
        void skipsNullAndBlankEntries() {
            final UUID valid = UUID.randomUUID();
            final String json = "[null,\"\",\"   \",\"" + valid + "\"]";

            assertThat(codec.deserialize(json)).containsExactly(valid);
        }

        @Test
        @DisplayName("skips a malformed UUID entry, keeping the valid ones")
        void skipsMalformedUuidEntry() {
            final UUID valid = UUID.randomUUID();
            final String json = "[\"not-a-uuid\",\"" + valid + "\"]";

            assertThat(codec.deserialize(json)).containsExactly(valid);
        }

        @Test
        @DisplayName("returns an empty list (graceful) for a malformed JSON payload")
        void malformedJsonYieldsEmptyList() {
            assertThat(codec.deserialize("{not json")).isEmpty();
        }
    }

    @Nested
    @DisplayName("serialize")
    class Serialize {

        @Test
        @DisplayName("returns \"[]\" for a null list")
        void nullYieldsEmptyArray() {
            assertThat(codec.serialize(null)).isEqualTo("[]");
        }

        @Test
        @DisplayName("returns \"[]\" for an empty list")
        void emptyYieldsEmptyArray() {
            assertThat(codec.serialize(List.of())).isEqualTo("[]");
        }

        @Test
        @DisplayName("writes a JSON array of stringified UUIDs")
        void writesArrayOfStrings() {
            final UUID a = UUID.randomUUID();
            final UUID b = UUID.randomUUID();

            assertThat(codec.serialize(List.of(a, b))).isEqualTo("[\"" + a + "\",\"" + b + "\"]");
        }

        @Test
        @DisplayName("skips null entries in the list")
        void skipsNullEntries() {
            final UUID valid = UUID.randomUUID();
            final List<UUID> ids = new ArrayList<>();
            ids.add(null);
            ids.add(valid);

            assertThat(codec.serialize(ids)).isEqualTo("[\"" + valid + "\"]");
        }
    }

    @Test
    @DisplayName("round-trips a list of UUIDs through serialize then deserialize")
    void roundTripPreservesIds() {
        final List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(codec.deserialize(codec.serialize(ids))).isEqualTo(ids);
    }
}
