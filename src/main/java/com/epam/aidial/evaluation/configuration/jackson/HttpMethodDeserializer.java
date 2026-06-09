package com.epam.aidial.evaluation.configuration.jackson;

import org.springframework.http.HttpMethod;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Jackson deserializer for Spring's HttpMethod (enum name as string).
 */
public class HttpMethodDeserializer extends ValueDeserializer<HttpMethod> {

    @Override
    public HttpMethod deserialize(JsonParser p, DeserializationContext ctxt) {
        String value = p.getText();
        return value == null || value.isBlank()
                ? null
                : HttpMethod.valueOf(value.trim().toUpperCase());
    }
}
