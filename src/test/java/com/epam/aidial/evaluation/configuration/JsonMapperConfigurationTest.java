package com.epam.aidial.evaluation.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.multipart.MultipartHttpMessageConverter;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.util.LinkedMultiValueMap;

class JsonMapperConfigurationTest {

    @Test
    @DisplayName("Multipart file bytes remain raw when the custom JSON converter precedes default part converters")
    void shouldWriteRawMultipartFileBytes() throws Exception {
        final var configuration = new JsonMapperConfiguration();
        final var jsonConverter = configuration.jacksonJsonHttpMessageConverter(configuration.objectMapper());
        final var converters = HttpMessageConverters.forClient()
                .registerDefaults()
                .addCustomConverter(jsonConverter)
                .build();
        final var multipartConverter = StreamSupport.stream(converters.spliterator(), false)
                .filter(MultipartHttpMessageConverter.class::isInstance)
                .map(MultipartHttpMessageConverter.class::cast)
                .findFirst()
                .orElseThrow();
        final var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("file", new ByteArrayResource("prompt\nhello\n".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "test-cases.csv";
            }
        });
        final var output = new MockHttpOutputMessage();

        multipartConverter.write(parts, MediaType.MULTIPART_FORM_DATA, output);

        assertThat(output.getBodyAsString(StandardCharsets.UTF_8))
                .contains("filename=\"test-cases.csv\"")
                .contains("prompt\nhello\n");
    }

    @Test
    @DisplayName("Explicit JSON multipart parts still use the custom JSON converter")
    void shouldWriteJsonMultipartParts() throws Exception {
        final var configuration = new JsonMapperConfiguration();
        final var jsonConverter = configuration.jacksonJsonHttpMessageConverter(configuration.objectMapper());
        final var multipartConverter = new MultipartHttpMessageConverter(List.of(jsonConverter));
        final var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        final var parts = new LinkedMultiValueMap<String, Object>();
        parts.add("metadata", new HttpEntity<>(Map.of("prompt", "hello"), headers));
        final var output = new MockHttpOutputMessage();

        multipartConverter.write(parts, MediaType.MULTIPART_FORM_DATA, output);

        assertThat(output.getBodyAsString(StandardCharsets.UTF_8))
                .contains("Content-Type: application/json")
                .contains("{\"prompt\":\"hello\"}");
    }
}
