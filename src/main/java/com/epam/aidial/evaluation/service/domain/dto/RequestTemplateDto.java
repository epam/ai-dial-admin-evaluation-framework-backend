package com.epam.aidial.evaluation.service.domain.dto;

import jakarta.validation.Valid;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestTemplateDto {

    private String urlTemplate;

    @Valid
    private List<KeyValueTemplateDto> queryParams;

    @Valid
    private List<KeyValueTemplateDto> headers;

    @Valid
    private RequestBodyDto body;
}
