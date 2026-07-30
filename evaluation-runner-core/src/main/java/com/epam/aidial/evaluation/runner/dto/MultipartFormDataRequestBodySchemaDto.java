package com.epam.aidial.evaluation.runner.dto;

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
public class MultipartFormDataRequestBodySchemaDto extends RequestBodySchemaDto {

    @Valid
    private List<FormPartSchemaDto> parts;

    @Override
    public String getContentType() {
        return "multipart/form-data";
    }
}
