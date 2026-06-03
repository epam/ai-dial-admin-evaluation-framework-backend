package com.epam.aidial.evaluation.service.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Polymorphic request body DTO serialization")
class RequestBodyDtoSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("RequestBodyDto hierarchy")
    class RequestBodyDtoTests {

        @Test
        @DisplayName("JsonRequestBodyDto round-trip preserves type and content")
        void jsonRequestBodyDtoRoundTrip() throws JsonProcessingException {
            JsonRequestBodyDto dto = JsonRequestBodyDto.builder()
                    .content(Map.of("prompt", "Hello", "temperature", 0.7))
                    .build();

            String json = objectMapper.writeValueAsString(dto);
            RequestBodyDto deserialized = objectMapper.readValue(json, RequestBodyDto.class);

            assertThat(deserialized).isInstanceOf(JsonRequestBodyDto.class);
            assertThat(json).contains("\"contentType\":\"application/json\"");
            JsonRequestBodyDto result = (JsonRequestBodyDto) deserialized;
            assertThat(result.getContent()).containsEntry("prompt", "Hello");
            assertThat(result.getContent()).containsEntry("temperature", 0.7);
            assertThat(result.getContentType()).isEqualTo("application/json");
        }

        @Test
        @DisplayName("MultipartFormDataRequestBodyDto round-trip preserves type and content")
        void multipartFormDataRequestBodyDtoRoundTrip() throws JsonProcessingException {
            FormPartDto textPart = FormPartDto.builder()
                    .name("field1")
                    .type(FormPartType.TEXT)
                    .value("some text")
                    .build();
            FormPartDto filePart = FormPartDto.builder()
                    .name("attachment")
                    .type(FormPartType.FILE)
                    .value("base64data")
                    .filename("doc.pdf")
                    .build();
            MultipartFormDataRequestBodyDto dto = MultipartFormDataRequestBodyDto.builder()
                    .content(List.of(textPart, filePart))
                    .build();

            String json = objectMapper.writeValueAsString(dto);
            RequestBodyDto deserialized = objectMapper.readValue(json, RequestBodyDto.class);

            assertThat(deserialized).isInstanceOf(MultipartFormDataRequestBodyDto.class);
            assertThat(json).contains("\"contentType\":\"multipart/form-data\"");
            MultipartFormDataRequestBodyDto result = (MultipartFormDataRequestBodyDto) deserialized;
            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent().get(0).getName()).isEqualTo("field1");
            assertThat(result.getContent().get(0).getType()).isEqualTo(FormPartType.TEXT);
            assertThat(result.getContent().get(0).getValue()).isEqualTo("some text");
            assertThat(result.getContent().get(1).getName()).isEqualTo("attachment");
            assertThat(result.getContent().get(1).getType()).isEqualTo(FormPartType.FILE);
            assertThat(result.getContent().get(1).getFilename()).isEqualTo("doc.pdf");
            assertThat(result.getContentType()).isEqualTo("multipart/form-data");
        }

        @Test
        @DisplayName("UrlEncodedFormRequestBodyDto round-trip preserves type and content")
        void urlEncodedFormRequestBodyDtoRoundTrip() throws JsonProcessingException {
            KeyValueTemplateDto entry1 =
                    KeyValueTemplateDto.builder().key("username").value("admin").build();
            KeyValueTemplateDto entry2 = KeyValueTemplateDto.builder()
                    .key("password")
                    .value("secret")
                    .build();
            UrlEncodedFormRequestBodyDto dto = UrlEncodedFormRequestBodyDto.builder()
                    .content(List.of(entry1, entry2))
                    .build();

            String json = objectMapper.writeValueAsString(dto);
            RequestBodyDto deserialized = objectMapper.readValue(json, RequestBodyDto.class);

            assertThat(deserialized).isInstanceOf(UrlEncodedFormRequestBodyDto.class);
            assertThat(json).contains("\"contentType\":\"application/x-www-form-urlencoded\"");
            UrlEncodedFormRequestBodyDto result = (UrlEncodedFormRequestBodyDto) deserialized;
            assertThat(result.getContent()).hasSize(2);
            assertThat(result.getContent().get(0).getKey()).isEqualTo("username");
            assertThat(result.getContent().get(0).getValue()).isEqualTo("admin");
            assertThat(result.getContent().get(1).getKey()).isEqualTo("password");
            assertThat(result.getContent().get(1).getValue()).isEqualTo("secret");
            assertThat(result.getContentType()).isEqualTo("application/x-www-form-urlencoded");
        }

        @Test
        @DisplayName("unknown contentType fails with InvalidTypeIdException")
        void unknownContentTypeFailsForRequestBody() {
            String json = """
                    {"contentType":"text/plain","content":"raw text"}""";

            assertThatThrownBy(() -> objectMapper.readValue(json, RequestBodyDto.class))
                    .isInstanceOf(InvalidTypeIdException.class)
                    .hasMessageContaining("text/plain");
        }
    }

    @Nested
    @DisplayName("RequestBodySchemaDto hierarchy")
    class RequestBodySchemaDtoTests {

        @Test
        @DisplayName("JsonRequestBodySchemaDto round-trip preserves type and schema")
        void jsonRequestBodySchemaDtoRoundTrip() throws JsonProcessingException {
            JsonRequestBodySchemaDto dto = JsonRequestBodySchemaDto.builder()
                    .schema(Map.of("type", "object", "required", List.of("prompt")))
                    .build();

            String json = objectMapper.writeValueAsString(dto);
            RequestBodySchemaDto deserialized = objectMapper.readValue(json, RequestBodySchemaDto.class);

            assertThat(deserialized).isInstanceOf(JsonRequestBodySchemaDto.class);
            assertThat(json).contains("\"contentType\":\"application/json\"");
            JsonRequestBodySchemaDto result = (JsonRequestBodySchemaDto) deserialized;
            assertThat(result.getSchema()).containsEntry("type", "object");
            assertThat(result.getContentType()).isEqualTo("application/json");
        }

        @Test
        @DisplayName("MultipartFormDataRequestBodySchemaDto round-trip preserves type and parts")
        void multipartFormDataRequestBodySchemaDtoRoundTrip() throws JsonProcessingException {
            FormPartSchemaDto partSchema = FormPartSchemaDto.builder()
                    .name("document")
                    .type(FormPartType.FILE)
                    .required(true)
                    .allowedContentTypes(List.of("application/pdf", "image/png"))
                    .maxSizeBytes(5_000_000L)
                    .build();
            MultipartFormDataRequestBodySchemaDto dto = MultipartFormDataRequestBodySchemaDto.builder()
                    .parts(List.of(partSchema))
                    .build();

            String json = objectMapper.writeValueAsString(dto);
            RequestBodySchemaDto deserialized = objectMapper.readValue(json, RequestBodySchemaDto.class);

            assertThat(deserialized).isInstanceOf(MultipartFormDataRequestBodySchemaDto.class);
            assertThat(json).contains("\"contentType\":\"multipart/form-data\"");
            MultipartFormDataRequestBodySchemaDto result = (MultipartFormDataRequestBodySchemaDto) deserialized;
            assertThat(result.getParts()).hasSize(1);
            assertThat(result.getParts().get(0).getName()).isEqualTo("document");
            assertThat(result.getParts().get(0).getType()).isEqualTo(FormPartType.FILE);
            assertThat(result.getParts().get(0).isRequired()).isTrue();
            assertThat(result.getParts().get(0).getAllowedContentTypes())
                    .containsExactly("application/pdf", "image/png");
            assertThat(result.getParts().get(0).getMaxSizeBytes()).isEqualTo(5_000_000L);
            assertThat(result.getContentType()).isEqualTo("multipart/form-data");
        }

        @Test
        @DisplayName("UrlEncodedFormRequestBodySchemaDto round-trip preserves type and schema")
        void urlEncodedFormRequestBodySchemaDtoRoundTrip() throws JsonProcessingException {
            UrlEncodedFormRequestBodySchemaDto dto = UrlEncodedFormRequestBodySchemaDto.builder()
                    .schema(Map.of("type", "object"))
                    .build();

            String json = objectMapper.writeValueAsString(dto);
            RequestBodySchemaDto deserialized = objectMapper.readValue(json, RequestBodySchemaDto.class);

            assertThat(deserialized).isInstanceOf(UrlEncodedFormRequestBodySchemaDto.class);
            assertThat(json).contains("\"contentType\":\"application/x-www-form-urlencoded\"");
            UrlEncodedFormRequestBodySchemaDto result = (UrlEncodedFormRequestBodySchemaDto) deserialized;
            assertThat(result.getSchema()).containsEntry("type", "object");
            assertThat(result.getContentType()).isEqualTo("application/x-www-form-urlencoded");
        }

        @Test
        @DisplayName("unknown contentType fails with InvalidTypeIdException")
        void unknownContentTypeFailsForRequestBodySchema() {
            String json = """
                    {"contentType":"text/plain","schema":{}}""";

            assertThatThrownBy(() -> objectMapper.readValue(json, RequestBodySchemaDto.class))
                    .isInstanceOf(InvalidTypeIdException.class)
                    .hasMessageContaining("text/plain");
        }
    }

    @Nested
    @DisplayName("ResolvedBodyDto hierarchy")
    class ResolvedBodyDtoTests {

        @Test
        @DisplayName("ResolvedJsonBodyDto round-trip preserves type and content")
        void resolvedJsonBodyDtoRoundTrip() throws JsonProcessingException {
            ResolvedJsonBodyDto dto = ResolvedJsonBodyDto.builder()
                    .content(Map.of("result", "success", "score", 42))
                    .build();

            String json = objectMapper.writeValueAsString(dto);
            ResolvedBodyDto deserialized = objectMapper.readValue(json, ResolvedBodyDto.class);

            assertThat(deserialized).isInstanceOf(ResolvedJsonBodyDto.class);
            assertThat(json).contains("\"contentType\":\"application/json\"");
            ResolvedJsonBodyDto result = (ResolvedJsonBodyDto) deserialized;
            assertThat(result.getContent()).containsEntry("result", "success");
            assertThat(result.getContent()).containsEntry("score", 42);
            assertThat(result.getContentType()).isEqualTo("application/json");
        }

        @Test
        @DisplayName("ResolvedMultipartBodyDto round-trip preserves type and parts")
        void resolvedMultipartBodyDtoRoundTrip() throws JsonProcessingException {
            ResolvedFormPartDto part = ResolvedFormPartDto.builder()
                    .name("file")
                    .type(FormPartType.FILE)
                    .resolvedValue("resolved-base64")
                    .filename("report.pdf")
                    .build();
            ResolvedMultipartBodyDto dto =
                    ResolvedMultipartBodyDto.builder().parts(List.of(part)).build();

            String json = objectMapper.writeValueAsString(dto);
            ResolvedBodyDto deserialized = objectMapper.readValue(json, ResolvedBodyDto.class);

            assertThat(deserialized).isInstanceOf(ResolvedMultipartBodyDto.class);
            assertThat(json).contains("\"contentType\":\"multipart/form-data\"");
            ResolvedMultipartBodyDto result = (ResolvedMultipartBodyDto) deserialized;
            assertThat(result.getParts()).hasSize(1);
            assertThat(result.getParts().get(0).getName()).isEqualTo("file");
            assertThat(result.getParts().get(0).getType()).isEqualTo(FormPartType.FILE);
            assertThat(result.getParts().get(0).getResolvedValue()).isEqualTo("resolved-base64");
            assertThat(result.getParts().get(0).getFilename()).isEqualTo("report.pdf");
            assertThat(result.getContentType()).isEqualTo("multipart/form-data");
        }

        @Test
        @DisplayName("ResolvedUrlEncodedBodyDto round-trip preserves type and entries")
        void resolvedUrlEncodedBodyDtoRoundTrip() throws JsonProcessingException {
            KeyValueTemplateDto entry =
                    KeyValueTemplateDto.builder().key("token").value("abc123").build();
            ResolvedUrlEncodedBodyDto dto =
                    ResolvedUrlEncodedBodyDto.builder().entries(List.of(entry)).build();

            String json = objectMapper.writeValueAsString(dto);
            ResolvedBodyDto deserialized = objectMapper.readValue(json, ResolvedBodyDto.class);

            assertThat(deserialized).isInstanceOf(ResolvedUrlEncodedBodyDto.class);
            assertThat(json).contains("\"contentType\":\"application/x-www-form-urlencoded\"");
            ResolvedUrlEncodedBodyDto result = (ResolvedUrlEncodedBodyDto) deserialized;
            assertThat(result.getEntries()).hasSize(1);
            assertThat(result.getEntries().get(0).getKey()).isEqualTo("token");
            assertThat(result.getEntries().get(0).getValue()).isEqualTo("abc123");
            assertThat(result.getContentType()).isEqualTo("application/x-www-form-urlencoded");
        }

        @Test
        @DisplayName("unknown contentType fails with InvalidTypeIdException")
        void unknownContentTypeFailsForResolvedBody() {
            String json = """
                    {"contentType":"text/plain","content":"raw"}""";

            assertThatThrownBy(() -> objectMapper.readValue(json, ResolvedBodyDto.class))
                    .isInstanceOf(InvalidTypeIdException.class)
                    .hasMessageContaining("text/plain");
        }
    }
}
