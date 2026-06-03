package com.epam.aidial.evaluation.configuration.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import org.springframework.http.HttpMethod;

/**
 * Jackson deserializer for Spring's HttpMethod (enum name as string).
 */
public class HttpMethodDeserializer extends JsonDeserializer<HttpMethod> {

    @Override
    public HttpMethod deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String value = p.getText();
        return value == null || value.isBlank()
                ? null
                : HttpMethod.valueOf(value.trim().toUpperCase());
    }
}
