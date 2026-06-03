package com.epam.aidial.evaluation.service.domain.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response-only DTO for the resolved-request preview API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolvedRequestDto {

    private String url;
    private List<KeyValueTemplateDto> queryParams;
    private List<KeyValueTemplateDto> headers;
    private ResolvedBodyDto body;
    private List<ValidationWarningDto> warnings;
}
