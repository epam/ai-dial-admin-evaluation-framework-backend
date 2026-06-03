package com.epam.aidial.evaluation.configuration.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import java.io.IOException;
import org.springframework.http.HttpMethod;

/**
 * Jackson serializer for Spring's HttpMethod (enum name as string).
 */
public class HttpMethodSerializer extends JsonSerializer<HttpMethod> {

    @Override
    public void serialize(HttpMethod value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else {
            gen.writeString(value.name());
        }
    }
}
