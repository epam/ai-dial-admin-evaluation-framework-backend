package com.epam.aidial.evaluation.configuration.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;

/**
 * Deserializes a JSON value into a String, accepting either a JSON string or a JSON object/array.
 * Used for metric provider schema fields (config_schema, input_schema, output_schema) so that
 * provider responses that send schemas as objects are normalized to a JSON string for internal use.
 */
public class JsonSchemaStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == null) {
            token = p.getCurrentToken();
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token == JsonToken.VALUE_STRING) {
            return p.getText();
        }
        if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
            JsonNode tree = p.readValueAsTree();
            ObjectMapper mapper = (ObjectMapper) p.getCodec();
            return mapper.writeValueAsString(tree);
        }
        // Scalar (number, boolean) - coerce to string for robustness
        if (token.isScalarValue()) {
            return p.getText();
        }
        return ctxt.reportInputMismatch(
                this, "Cannot deserialize schema field: expected string or JSON object/array, got %s", token);
    }
}
