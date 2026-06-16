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
public class ResolvedJsonBodyDto extends ResolvedBodyDto {

    private Map<String, Object> content;

    @Override
    public String getContentType() {
        return "application/json";
    }
}
