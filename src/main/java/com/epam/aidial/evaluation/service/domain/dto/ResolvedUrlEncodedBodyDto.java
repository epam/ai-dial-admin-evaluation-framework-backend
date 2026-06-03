package com.epam.aidial.evaluation.service.domain.dto;

import java.util.List;
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
public class ResolvedUrlEncodedBodyDto extends ResolvedBodyDto {

    private List<KeyValueTemplateDto> entries;

    @Override
    public String getContentType() {
        return "application/x-www-form-urlencoded";
    }
}
