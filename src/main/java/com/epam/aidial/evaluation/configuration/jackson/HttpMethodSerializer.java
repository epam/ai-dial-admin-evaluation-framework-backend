package com.epam.aidial.evaluation.configuration.jackson;

import org.springframework.http.HttpMethod;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Jackson serializer for Spring's HttpMethod (enum name as string).
 */
public class HttpMethodSerializer extends ValueSerializer<HttpMethod> {

    @Override
    public void serialize(HttpMethod value, JsonGenerator gen, SerializationContext serializers) {
        if (value == null) {
            gen.writeNull();
        } else {
            gen.writeString(value.name());
        }
    }
}
