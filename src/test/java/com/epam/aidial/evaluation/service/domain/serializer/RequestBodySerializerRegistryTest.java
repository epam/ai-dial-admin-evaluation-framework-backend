package com.epam.aidial.evaluation.service.domain.serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.service.domain.JsonRequestBodySerializer;
import com.epam.aidial.evaluation.service.domain.MultipartFormDataRequestBodySerializer;
import com.epam.aidial.evaluation.service.domain.RequestBodySerializerRegistry;
import com.epam.aidial.evaluation.service.domain.SerializedBody;
import com.epam.aidial.evaluation.service.domain.UrlEncodedFormRequestBodySerializer;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedMultipartBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedUrlEncodedBodyDto;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;

@DisplayName("RequestBodySerializerRegistry")
@ExtendWith(MockitoExtension.class)
class RequestBodySerializerRegistryTest {

    @Mock
    private JsonRequestBodySerializer jsonSerializer;

    @Mock
    private MultipartFormDataRequestBodySerializer multipartSerializer;

    @Mock
    private UrlEncodedFormRequestBodySerializer urlEncodedSerializer;

    private RequestBodySerializerRegistry registry;

    @BeforeEach
    void setUp() {
        registry =
                new RequestBodySerializerRegistry(List.of(jsonSerializer, multipartSerializer, urlEncodedSerializer));
    }

    @Nested
    @DisplayName("serializer selection")
    class SerializerSelection {

        @Test
        @DisplayName("should select JsonRequestBodySerializer for ResolvedJsonBodyDto")
        void shouldSelectJsonSerializerForJsonBody() {
            ResolvedJsonBodyDto body = ResolvedJsonBodyDto.builder()
                    .content(Map.of("key", "value"))
                    .build();
            SerializedBody expectedResult = new SerializedBody(MediaType.APPLICATION_JSON, body.getContent());

            when(jsonSerializer.supports(body)).thenReturn(true);
            when(jsonSerializer.serialize(body)).thenReturn(expectedResult);

            SerializedBody result = registry.serialize(body);

            assertThat(result).isSameAs(expectedResult);
            assertThat(result.contentType()).isEqualTo(MediaType.APPLICATION_JSON);
        }

        @Test
        @DisplayName("should select MultipartFormDataRequestBodySerializer for ResolvedMultipartBodyDto")
        void shouldSelectMultipartSerializerForMultipartBody() {
            ResolvedMultipartBodyDto body = ResolvedMultipartBodyDto.builder()
                    .parts(Collections.emptyList())
                    .build();
            SerializedBody expectedResult = new SerializedBody(MediaType.MULTIPART_FORM_DATA, Map.of());

            when(jsonSerializer.supports(body)).thenReturn(false);
            when(multipartSerializer.supports(body)).thenReturn(true);
            when(multipartSerializer.serialize(body)).thenReturn(expectedResult);

            SerializedBody result = registry.serialize(body);

            assertThat(result).isSameAs(expectedResult);
            assertThat(result.contentType()).isEqualTo(MediaType.MULTIPART_FORM_DATA);
        }

        @Test
        @DisplayName("should select UrlEncodedFormRequestBodySerializer for ResolvedUrlEncodedBodyDto")
        void shouldSelectUrlEncodedSerializerForUrlEncodedBody() {
            ResolvedUrlEncodedBodyDto body = ResolvedUrlEncodedBodyDto.builder()
                    .entries(Collections.emptyList())
                    .build();
            SerializedBody expectedResult = new SerializedBody(MediaType.APPLICATION_FORM_URLENCODED, Map.of());

            when(jsonSerializer.supports(body)).thenReturn(false);
            when(multipartSerializer.supports(body)).thenReturn(false);
            when(urlEncodedSerializer.supports(body)).thenReturn(true);
            when(urlEncodedSerializer.serialize(body)).thenReturn(expectedResult);

            SerializedBody result = registry.serialize(body);

            assertThat(result).isSameAs(expectedResult);
            assertThat(result.contentType()).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED);
        }
    }

    @Nested
    @DisplayName("error handling")
    class ErrorHandling {

        @Test
        @DisplayName("should throw IllegalStateException when no serializer supports the body type")
        void shouldThrowForUnsupportedBodyType() {
            ResolvedBodyDto unknownBody = new ResolvedBodyDto() {
                @Override
                public String getContentType() {
                    return "application/xml";
                }
            };

            when(jsonSerializer.supports(any())).thenReturn(false);
            when(multipartSerializer.supports(any())).thenReturn(false);
            when(urlEncodedSerializer.supports(any())).thenReturn(false);

            assertThatThrownBy(() -> registry.serialize(unknownBody))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No serializer found for body type");
        }

        @Test
        @DisplayName("should return null when body is null")
        void shouldReturnNullForNullBody() {
            SerializedBody result = registry.serialize(null);

            assertThat(result).isNull();
        }
    }
}
