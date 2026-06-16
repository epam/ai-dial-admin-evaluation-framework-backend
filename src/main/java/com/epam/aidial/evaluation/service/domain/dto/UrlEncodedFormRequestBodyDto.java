package com.epam.aidial.evaluation.service.domain.dto;

import jakarta.validation.Valid;
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
public class UrlEncodedFormRequestBodyDto extends RequestBodyDto {

    @Valid
    private List<KeyValueTemplateDto> content;

    @Override
    public String getContentType() {
        return "application/x-www-form-urlencoded";
    }
}
