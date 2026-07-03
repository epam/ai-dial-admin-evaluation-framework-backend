package com.epam.aidial.evaluation.configuration.jackson;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Deserializes a JSON value into a String, accepting either a JSON string or a JSON object/array.
 * Used for metric provider schema fields (config_schema, input_schema, output_schema) so that
 * provider responses that send schemas as objects are normalized to a JSON string for internal use.
 */
public class JsonSchemaStringDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) {
        JsonToken token = p.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token == JsonToken.VALUE_STRING) {
            return p.getString();
        }
        if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
            JsonNode tree = p.readValueAsTree();
            return tree.toString();
        }
        // Scalar (number, boolean) - coerce to string for robustness
        if (token != null && token.isScalarValue()) {
            return p.getString();
        }
        return ctxt.reportInputMismatch(
                this, "Cannot deserialize schema field: expected string or JSON object/array, got %s", token);
    }
}
