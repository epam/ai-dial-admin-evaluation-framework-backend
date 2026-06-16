package com.epam.aidial.evaluation.service.domain.dto;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class UrlEncodedFormRequestBodySchemaDto extends RequestBodySchemaDto {

    private Map<String, Object> schema;

    @Override
    public String getContentType() {
        return "application/x-www-form-urlencoded";
    }
}
