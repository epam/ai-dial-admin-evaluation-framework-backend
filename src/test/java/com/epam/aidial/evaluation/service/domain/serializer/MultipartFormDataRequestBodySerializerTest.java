package com.epam.aidial.evaluation.service.domain.serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DialCoreClientException;
import com.epam.aidial.evaluation.client.dialcore.DialFileClient;
import com.epam.aidial.evaluation.service.domain.DialFileRefResolver;
import com.epam.aidial.evaluation.service.domain.MultipartFormDataRequestBodySerializer;
import com.epam.aidial.evaluation.service.domain.SerializedBody;
import com.epam.aidial.evaluation.service.domain.dto.FormPartType;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedFormPartDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedMultipartBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedUrlEncodedBodyDto;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;

@DisplayName("MultipartFormDataRequestBodySerializer")
@ExtendWith(MockitoExtension.class)
class MultipartFormDataRequestBodySerializerTest {

    @Mock
    private DialFileClient dialFileClient;

    @Mock
    private DialFileRefResolver dialFileRefResolver;

    @InjectMocks
    private MultipartFormDataRequestBodySerializer serializer;

    @Nested
    @DisplayName("supports")
    class Supports {

        @Test
        @DisplayName("should return true for ResolvedMultipartBodyDto")
        void shouldReturnTrueForMultipartBody() {
            ResolvedMultipartBodyDto body = ResolvedMultipartBodyDto.builder()
                    .parts(Collections.emptyList())
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
        @DisplayName("should produce MULTIPART_FORM_DATA content type")
        void shouldProduceMultipartFormDataContentType() {
            ResolvedMultipartBodyDto body = ResolvedMultipartBodyDto.builder()
                    .parts(List.of(ResolvedFormPartDto.builder()
                            .name("field")
                            .type(FormPartType.TEXT)
                            .resolvedValue("value")
                            .build()))
                    .build();

            SerializedBody result = serializer.serialize(body);

            assertThat(result.contentType()).isEqualTo(MediaType.MULTIPART_FORM_DATA);
        }

        @Test
        @DisplayName("should produce MultiValueMap body")
        void shouldProduceMultiValueMapBody() {
            ResolvedMultipartBodyDto body = ResolvedMultipartBodyDto.builder()
                    .parts(List.of(ResolvedFormPartDto.builder()
                            .name("field")
                            .type(FormPartType.TEXT)
                            .resolvedValue("value")
                            .build()))
                    .build();

            SerializedBody result = serializer.serialize(body);

            assertThat(result.body()).isInstanceOf(MultiValueMap.class);
        }

        @Test
        @DisplayName("should include text part with correct name and value")
        @SuppressWarnings("unchecked")
        void shouldIncludeTextPartWithCorrectNameAndValue() {
            ResolvedMultipartBodyDto body = ResolvedMultipartBodyDto.builder()
                    .parts(List.of(ResolvedFormPartDto.builder()
                            .name("description")
                            .type(FormPartType.TEXT)
                            .resolvedValue("test description")
                            .build()))
                    .build();

            SerializedBody result = serializer.serialize(body);

            MultiValueMap<String, HttpEntity<?>> multipartMap = (MultiValueMap<String, HttpEntity<?>>) result.body();
            assertThat(multipartMap).containsKey("description");
            HttpEntity<?> part = multipartMap.getFirst("description");
            assertThat(part).isNotNull();
            assertThat(part.getBody()).isEqualTo("test description");
        }

        @Test
        @DisplayName("should treat null text value as empty string")
        @SuppressWarnings("unchecked")
        void shouldTreatNullTextValueAsEmptyString() {
            ResolvedMultipartBodyDto body = ResolvedMultipartBodyDto.builder()
                    .parts(List.of(ResolvedFormPartDto.builder()
                            .name("emptyField")
                            .type(FormPartType.TEXT)
                            .resolvedValue(null)
                            .build()))
                    .build();

            SerializedBody result = serializer.serialize(body);

            MultiValueMap<String, HttpEntity<?>> multipartMap = (MultiValueMap<String, HttpEntity<?>>) result.body();
            HttpEntity<?> part = multipartMap.getFirst("emptyField");
            assertThat(part).isNotNull();
            assertThat(part.getBody()).isEqualTo("");
        }

        @Test
        @DisplayName("should include file part by downloading bytes from DIAL")
        @SuppressWarnings("unchecked")
        void shouldIncludeFilePartFromDial() {
            String fileRef = "@ef/suites/abc/report.pdf";
            String realPath = "real-bucket/suites/abc/report.pdf";
            byte[] fileBytes = "file content".getBytes(StandardCharsets.UTF_8);
            when(dialFileRefResolver.resolveToRealPath(fileRef)).thenReturn(realPath);
            when(dialFileClient.download(realPath)).thenReturn(fileBytes);

            ResolvedMultipartBodyDto body = ResolvedMultipartBodyDto.builder()
                    .parts(List.of(ResolvedFormPartDto.builder()
                            .name("document")
                            .type(FormPartType.FILE)
                            .resolvedValue(fileRef)
                            .filename("report.pdf")
                            .build()))
                    .build();

            SerializedBody result = serializer.serialize(body);

            MultiValueMap<String, HttpEntity<?>> multipartMap = (MultiValueMap<String, HttpEntity<?>>) result.body();
            assertThat(multipartMap).containsKey("document");
            HttpEntity<?> part = multipartMap.getFirst("document");
            assertThat(part).isNotNull();
            assertThat(part.getHeaders().getContentDisposition().getFilename()).isEqualTo("report.pdf");
        }

        @Test
        @DisplayName("should use filename from ref when filename is null")
        @SuppressWarnings("unchecked")
        void shouldUseFilenameFromRefWhenNull() {
            String fileRef = "@ef/suites/abc/data.csv";
            String realPath = "real-bucket/suites/abc/data.csv";
            byte[] fileBytes = "data".getBytes(StandardCharsets.UTF_8);
            when(dialFileRefResolver.resolveToRealPath(fileRef)).thenReturn(realPath);
            when(dialFileClient.download(realPath)).thenReturn(fileBytes);
            when(dialFileRefResolver.extractFilename(fileRef)).thenReturn("data.csv");

            ResolvedMultipartBodyDto body = ResolvedMultipartBodyDto.builder()
                    .parts(List.of(ResolvedFormPartDto.builder()
                            .name("upload")
                            .type(FormPartType.FILE)
                            .resolvedValue(fileRef)
                            .filename(null)
                            .build()))
                    .build();

            SerializedBody result = serializer.serialize(body);

            MultiValueMap<String, HttpEntity<?>> multipartMap = (MultiValueMap<String, HttpEntity<?>>) result.body();
            HttpEntity<?> part = multipartMap.getFirst("upload");
            assertThat(part).isNotNull();
            assertThat(part.getHeaders().getContentDisposition().getFilename()).isEqualTo("data.csv");
        }

        @Test
        @DisplayName("should skip file part when resolved value is null")
        @SuppressWarnings("unchecked")
        void shouldSkipFilePartWhenResolvedValueIsNull() {
            ResolvedMultipartBodyDto body = ResolvedMultipartBodyDto.builder()
                    .parts(List.of(ResolvedFormPartDto.builder()
                            .name("upload")
                            .type(FormPartType.FILE)
                            .resolvedValue(null)
                            .filename("report.pdf")
                            .build()))
                    .build();

            SerializedBody result = serializer.serialize(body);

            MultiValueMap<String, HttpEntity<?>> multipartMap = (MultiValueMap<String, HttpEntity<?>>) result.body();
            assertThat(multipartMap).doesNotContainKey("upload");
        }

        @Test
        @DisplayName("should skip file part when resolved value is blank")
        @SuppressWarnings("unchecked")
        void shouldSkipFilePartWhenResolvedValueIsBlank() {
            ResolvedMultipartBodyDto body = ResolvedMultipartBodyDto.builder()
                    .parts(List.of(ResolvedFormPartDto.builder()
                            .name("upload")
                            .type(FormPartType.FILE)
                            .resolvedValue("   ")
                            .filename("report.pdf")
                            .build()))
                    .build();

            SerializedBody result = serializer.serialize(body);

            MultiValueMap<String, HttpEntity<?>> multipartMap = (MultiValueMap<String, HttpEntity<?>>) result.body();
            assertThat(multipartMap).doesNotContainKey("upload");
        }

        @Test
        @DisplayName("should handle null parts list")
        @SuppressWarnings("unchecked")
        void shouldHandleNullPartsList() {
            ResolvedMultipartBodyDto body =
                    ResolvedMultipartBodyDto.builder().parts(null).build();

            SerializedBody result = serializer.serialize(body);

            assertThat(result.contentType()).isEqualTo(MediaType.MULTIPART_FORM_DATA);
            MultiValueMap<String, HttpEntity<?>> multipartMap = (MultiValueMap<String, HttpEntity<?>>) result.body();
            assertThat(multipartMap).isEmpty();
        }

        @Test
        @DisplayName("should handle mixed text and file parts")
        @SuppressWarnings("unchecked")
        void shouldHandleMixedTextAndFileParts() {
            String fileRef = "@ef/suites/abc/doc.pdf";
            String realPath = "real-bucket/suites/abc/doc.pdf";
            byte[] fileBytes = "binary".getBytes(StandardCharsets.UTF_8);
            when(dialFileRefResolver.resolveToRealPath(fileRef)).thenReturn(realPath);
            when(dialFileClient.download(realPath)).thenReturn(fileBytes);

            ResolvedMultipartBodyDto body = ResolvedMultipartBodyDto.builder()
                    .parts(List.of(
                            ResolvedFormPartDto.builder()
                                    .name("title")
                                    .type(FormPartType.TEXT)
                                    .resolvedValue("My Document")
                                    .build(),
                            ResolvedFormPartDto.builder()
                                    .name("attachment")
                                    .type(FormPartType.FILE)
                                    .resolvedValue(fileRef)
                                    .filename("doc.pdf")
                                    .build()))
                    .build();

            SerializedBody result = serializer.serialize(body);

            MultiValueMap<String, HttpEntity<?>> multipartMap = (MultiValueMap<String, HttpEntity<?>>) result.body();
            assertThat(multipartMap).containsKey("title");
            assertThat(multipartMap).containsKey("attachment");

            HttpEntity<?> textPart = multipartMap.getFirst("title");
            assertThat(textPart).isNotNull();
            assertThat(textPart.getBody()).isEqualTo("My Document");

            HttpEntity<?> filePart = multipartMap.getFirst("attachment");
            assertThat(filePart).isNotNull();
            assertThat(filePart.getHeaders().getContentDisposition().getFilename())
                    .isEqualTo("doc.pdf");
        }

        @Test
        @DisplayName("should handle public path file download")
        @SuppressWarnings("unchecked")
        void shouldHandlePublicPathFileDownload() {
            String fileRef = "public/datasets/eval-data.csv";
            String realPath = "public/datasets/eval-data.csv";
            byte[] fileBytes = "public data".getBytes(StandardCharsets.UTF_8);
            when(dialFileRefResolver.resolveToRealPath(fileRef)).thenReturn(realPath);
            when(dialFileClient.download(realPath)).thenReturn(fileBytes);

            ResolvedMultipartBodyDto body = ResolvedMultipartBodyDto.builder()
                    .parts(List.of(ResolvedFormPartDto.builder()
                            .name("dataset")
                            .type(FormPartType.FILE)
                            .resolvedValue(fileRef)
                            .filename("eval-data.csv")
                            .build()))
                    .build();

            SerializedBody result = serializer.serialize(body);

            MultiValueMap<String, HttpEntity<?>> multipartMap = (MultiValueMap<String, HttpEntity<?>>) result.body();
            assertThat(multipartMap).containsKey("dataset");
            HttpEntity<?> part = multipartMap.getFirst("dataset");
            assertThat(part).isNotNull();
            assertThat(part.getHeaders().getContentDisposition().getFilename()).isEqualTo("eval-data.csv");
        }

        @Test
        @DisplayName("should propagate exception when file download fails")
        void shouldPropagateExceptionWhenFileDownloadFails() {
            String fileRef = "@ef/suites/abc/missing.pdf";
            String realPath = "real-bucket/suites/abc/missing.pdf";
            when(dialFileRefResolver.resolveToRealPath(fileRef)).thenReturn(realPath);
            when(dialFileClient.download(realPath))
                    .thenThrow(new DialCoreClientException(
                            org.springframework.http.HttpStatus.NOT_FOUND, "File not found"));

            ResolvedMultipartBodyDto body = ResolvedMultipartBodyDto.builder()
                    .parts(List.of(ResolvedFormPartDto.builder()
                            .name("document")
                            .type(FormPartType.FILE)
                            .resolvedValue(fileRef)
                            .filename("missing.pdf")
                            .build()))
                    .build();

            assertThatThrownBy(() -> serializer.serialize(body))
                    .isInstanceOf(DialCoreClientException.class)
                    .hasMessageContaining("File not found");
        }
    }
}
