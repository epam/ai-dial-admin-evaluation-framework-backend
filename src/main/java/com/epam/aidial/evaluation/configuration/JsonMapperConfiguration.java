package com.epam.aidial.evaluation.configuration;

import com.epam.aidial.evaluation.configuration.jackson.HttpMethodDeserializer;
import com.epam.aidial.evaluation.configuration.jackson.HttpMethodSerializer;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

@Configuration
@LogExecution
public class JsonMapperConfiguration {

    @Bean
    @Primary
    public JsonMapper objectMapper() {
        return createJsonMapper();
    }

    /**
     * SpringDoc's {@code /v3/api-docs} endpoint returns the OpenAPI spec as a {@code byte[]}
     * (already-serialized JSON) with {@code application/json} content type. Because this custom
     * converter bean is placed at the front of the converter list, under Spring Framework 7 /
     * Jackson 3 it intercepts that {@code byte[]} and serializes it as a Base64 JSON string,
     * so Swagger UI receives garbage and cannot find the {@code openapi} version field.
     * Declining {@code byte[]} here lets the default {@code ByteArrayHttpMessageConverter}
     * (which supports {@code *}/{@code *}) write the bytes verbatim.
     */
    @Bean
    public JacksonJsonHttpMessageConverter jacksonJsonHttpMessageConverter(JsonMapper objectMapper) {
        return new JacksonJsonHttpMessageConverter(objectMapper) {
            @Override
            public boolean canWrite(ResolvableType targetType, Class<?> valueType, MediaType mediaType) {
                if (byte[].class.equals(valueType)) {
                    return false;
                }
                return super.canWrite(targetType, valueType, mediaType);
            }
        };
    }

    private static JsonMapper createJsonMapper() {
        SimpleModule httpMethodModule = new SimpleModule();
        httpMethodModule.addSerializer(HttpMethod.class, new HttpMethodSerializer());
        httpMethodModule.addDeserializer(HttpMethod.class, new HttpMethodDeserializer());

        return JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .changeDefaultPropertyInclusion(
                        v -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .addModule(httpMethodModule)
                .build();
    }
}
