package com.epam.aidial.evaluation.service.domain.serializer;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.service.domain.JsonRequestBodySerializer;
import com.epam.aidial.evaluation.service.domain.SerializedBody;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedMultipartBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedUrlEncodedBodyDto;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

@DisplayName("JsonRequestBodySerializer")
class JsonRequestBodySerializerTest {

    private final JsonRequestBodySerializer serializer = new JsonRequestBodySerializer();

    @Nested
    @DisplayName("supports")
    class Supports {

        @Test
        @DisplayName("should return true for ResolvedJsonBodyDto")
        void shouldReturnTrueForJsonBody() {
            ResolvedJsonBodyDto body = ResolvedJsonBodyDto.builder()
                    .content(Map.of("key", "value"))
                    .build();

            assertThat(serializer.supports(body)).isTrue();
        }

        @Test
        @DisplayName("should return false for ResolvedMultipartBodyDto")
        void shouldReturnFalseForMultipartBody() {
            ResolvedMultipartBodyDto body = ResolvedMultipartBodyDto.builder()
                    .parts(Collections.emptyList())
                    .build();

            assertThat(serializer.supports(body)).isFalse();
        }

        @Test
        @DisplayName("should return false for ResolvedUrlEncodedBodyDto")
        void shouldReturnFalseForUrlEncodedBody() {
            ResolvedUrlEncodedBodyDto body = ResolvedUrlEncodedBodyDto.builder()
                    .entries(Collections.emptyList())
                    .build();

            assertThat(serializer.supports(body)).isFalse();
        }
    }

    @Nested
    @DisplayName("serialize")
    class Serialize {

        @Test
        @DisplayName("should produce APPLICATION_JSON content type")
        void shouldProduceJsonContentType() {
            ResolvedJsonBodyDto body = ResolvedJsonBodyDto.builder()
                    .content(Map.of("prompt", "Hello"))
                    .build();

            SerializedBody result = serializer.serialize(body);

            assertThat(result.contentType()).isEqualTo(MediaType.APPLICATION_JSON);
        }

        @Test
        @DisplayName("should produce body matching the content map")
        void shouldProduceBodyMatchingContentMap() {
            Map<String, Object> content = Map.of("prompt", "Hello", "model", "gpt-4");
            ResolvedJsonBodyDto body =
                    ResolvedJsonBodyDto.builder().content(content).build();

            SerializedBody result = serializer.serialize(body);

            assertThat(result.body()).isEqualTo(content);
        }

        @Test
        @DisplayName("should handle empty content map")
        void shouldHandleEmptyContentMap() {
            ResolvedJsonBodyDto body = ResolvedJsonBodyDto.builder()
                    .content(Collections.emptyMap())
                    .build();

            SerializedBody result = serializer.serialize(body);

            assertThat(result.contentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(result.body()).isEqualTo(Collections.emptyMap());
        }

        @Test
        @DisplayName("should handle null content map")
        void shouldHandleNullContentMap() {
            ResolvedJsonBodyDto body =
                    ResolvedJsonBodyDto.builder().content(null).build();

            SerializedBody result = serializer.serialize(body);

            assertThat(result.contentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(result.body()).isNull();
        }
    }
}
