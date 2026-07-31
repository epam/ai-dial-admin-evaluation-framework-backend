package com.epam.aidial.evaluation.runner.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedMultipartBodyDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedUrlEncodedBodyDto;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;

@DisplayName("UrlEncodedFormRequestBodySerializer")
class UrlEncodedFormRequestBodySerializerTest {

    private final UrlEncodedFormRequestBodySerializer serializer = new UrlEncodedFormRequestBodySerializer();

    @Nested
    @DisplayName("supports")
    class Supports {

        @Test
        @DisplayName("should return true for ResolvedUrlEncodedBodyDto")
        void shouldReturnTrueForUrlEncodedBody() {
            ResolvedUrlEncodedBodyDto body = ResolvedUrlEncodedBodyDto.builder()
                    .entries(Collections.emptyList())
                    .build();

            assertThat(serializer.supports(body)).isTrue();
        }

        @Test
        @DisplayName("should return false for ResolvedJsonBodyDto")
        void shouldReturnFalseForJsonBody() {
            ResolvedJsonBodyDto body = ResolvedJsonBodyDto.builder()
                    .content(Map.of("key", "value"))
                    .build();

            assertThat(serializer.supports(body)).isFalse();
        }

        @Test
        @DisplayName("should return false for ResolvedMultipartBodyDto")
        void shouldReturnFalseForMultipartBody() {
            ResolvedMultipartBodyDto body = ResolvedMultipartBodyDto.builder()
                    .parts(Collections.emptyList())
                    .build();

            assertThat(serializer.supports(body)).isFalse();
        }
    }

    @Nested
    @DisplayName("serialize")
    class Serialize {

        @Test
        @DisplayName("should produce APPLICATION_FORM_URLENCODED content type")
        void shouldProduceFormUrlencodedContentType() {
            ResolvedUrlEncodedBodyDto body = ResolvedUrlEncodedBodyDto.builder()
                    .entries(List.of(KeyValueTemplateDto.builder()
                            .key("username")
                            .value("admin")
                            .build()))
                    .build();

            SerializedBody result = serializer.serialize(body);

            assertThat(result.contentType()).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
        }

        @Test
        @DisplayName("should produce MultiValueMap body with correct entries")
        @SuppressWarnings("unchecked")
        void shouldProduceMultiValueMapBody() {
            ResolvedUrlEncodedBodyDto body = ResolvedUrlEncodedBodyDto.builder()
                    .entries(List.of(
                            KeyValueTemplateDto.builder()
                                    .key("username")
                                    .value("admin")
                                    .build(),
                            KeyValueTemplateDto.builder()
                                    .key("password")
                                    .value("secret")
                                    .build()))
                    .build();

            SerializedBody result = serializer.serialize(body);

            assertThat(result.body()).isInstanceOf(MultiValueMap.class);
            MultiValueMap<String, String> formData = (MultiValueMap<String, String>) result.body();
            assertThat(formData.getFirst("username")).isEqualTo("admin");
            assertThat(formData.getFirst("password")).isEqualTo("secret");
        }

        @Test
        @DisplayName("should handle duplicate keys by adding multiple values")
        @SuppressWarnings("unchecked")
        void shouldHandleDuplicateKeys() {
            ResolvedUrlEncodedBodyDto body = ResolvedUrlEncodedBodyDto.builder()
                    .entries(List.of(
                            KeyValueTemplateDto.builder()
                                    .key("tag")
                                    .value("java")
                                    .build(),
                            KeyValueTemplateDto.builder()
                                    .key("tag")
                                    .value("spring")
                                    .build()))
                    .build();

            SerializedBody result = serializer.serialize(body);

            MultiValueMap<String, String> formData = (MultiValueMap<String, String>) result.body();
            assertThat(formData.get("tag")).containsExactly("java", "spring");
        }

        @Test
        @DisplayName("should treat null value as empty string")
        @SuppressWarnings("unchecked")
        void shouldTreatNullValueAsEmptyString() {
            ResolvedUrlEncodedBodyDto body = ResolvedUrlEncodedBodyDto.builder()
                    .entries(List.of(KeyValueTemplateDto.builder()
                            .key("emptyParam")
                            .value(null)
                            .build()))
                    .build();

            SerializedBody result = serializer.serialize(body);

            MultiValueMap<String, String> formData = (MultiValueMap<String, String>) result.body();
            assertThat(formData.getFirst("emptyParam")).isEmpty();
        }

        @Test
        @DisplayName("should handle null entries list")
        @SuppressWarnings("unchecked")
        void shouldHandleNullEntriesList() {
            ResolvedUrlEncodedBodyDto body =
                    ResolvedUrlEncodedBodyDto.builder().entries(null).build();

            SerializedBody result = serializer.serialize(body);

            assertThat(result.contentType()).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> formData = (MultiValueMap<String, String>) result.body();
            assertThat(formData).isEmpty();
        }

        @Test
        @DisplayName("should handle empty entries list")
        @SuppressWarnings("unchecked")
        void shouldHandleEmptyEntriesList() {
            ResolvedUrlEncodedBodyDto body = ResolvedUrlEncodedBodyDto.builder()
                    .entries(Collections.emptyList())
                    .build();

            SerializedBody result = serializer.serialize(body);

            assertThat(result.contentType()).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
            MultiValueMap<String, String> formData = (MultiValueMap<String, String>) result.body();
            assertThat(formData).isEmpty();
        }
    }
}
