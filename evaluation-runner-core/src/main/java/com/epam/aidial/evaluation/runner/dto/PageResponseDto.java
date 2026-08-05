package com.epam.aidial.evaluation.runner.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponseDto<T> {

    private List<T> content;

    @Schema(example = "0")
    private int page;

    @Schema(example = "20")
    private int size;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(example = "42")
    private Long totalElements;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(example = "3")
    private Integer totalPages;
}
