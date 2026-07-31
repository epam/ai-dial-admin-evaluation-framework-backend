package com.epam.aidial.evaluation.service.domain.analytics;

import com.epam.aidial.evaluation.data.db.analytics.model.cursor.Cursor;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@LogExecution
@RequiredArgsConstructor
public class CursorCodec {

    private final ObjectMapper objectMapper;

    public String encode(Cursor cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(cursor);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to encode cursor", e);
        }
    }

    public Cursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            return objectMapper.readValue(decoded, Cursor.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cursor: " + e.getMessage(), e);
        }
    }
}
