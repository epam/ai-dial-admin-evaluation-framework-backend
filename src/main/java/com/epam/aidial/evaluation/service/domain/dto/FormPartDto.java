package com.epam.aidial.evaluation.service.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormPartDto {

    @NotBlank
    private String name;

    @NotNull
    private FormPartType type;

    @NotNull
    private Object value;

    private String filename;
}
