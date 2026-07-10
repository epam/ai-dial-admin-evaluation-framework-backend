package com.epam.aidial.evaluation.client.dialcore.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DIAL Core features object (model or application).
 * Holds arbitrary key-value pairs as the feature set can vary.
 */
@Data
@NoArgsConstructor
public class DialCoreFeaturesDto {

    private Map<String, Object> additionalProperties = new LinkedHashMap<>();

    public void setAdditionalProperty(String name, Object value) {
        this.additionalProperties.put(name, value);
    }
}
