package com.epam.aidial.evaluation.configuration;

import com.epam.aidial.evaluation.configuration.jackson.HttpMethodDeserializer;
import com.epam.aidial.evaluation.configuration.jackson.HttpMethodSerializer;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

@Configuration
public class JsonMapperConfiguration {

    @Bean
    @Primary
    public JsonMapper objectMapper() {
        return createJsonMapper();
    }

    @Bean
    public RestTemplateCustomizer restTemplateCustomizer(JsonMapper objectMapper) {
        return restTemplate -> restTemplate.getMessageConverters().stream()
                .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast)
                .forEach(c -> c.setObjectMapper(objectMapper));
    }

    private static JsonMapper createJsonMapper() {
        SimpleModule httpMethodModule = new SimpleModule();
        httpMethodModule.addSerializer(HttpMethod.class, new HttpMethodSerializer());
        httpMethodModule.addDeserializer(HttpMethod.class, new HttpMethodDeserializer());

        return JsonMapper.builder()
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .addModule(new JavaTimeModule())
                .addModule(httpMethodModule)
                .build();
    }
}
