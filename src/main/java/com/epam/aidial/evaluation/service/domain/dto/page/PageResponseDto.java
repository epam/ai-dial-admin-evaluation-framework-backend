package com.epam.aidial.evaluation.service.domain.dto.page;

import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;
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

    public static <T, U> PageResponseDto<U> from(Page<T> domainPage, Function<T, U> mapper) {
        return from(domainPage, mapper, true);
    }

    public static <T, U> PageResponseDto<U> from(Page<T> domainPage, Function<T, U> mapper, boolean includeTotalCount) {
        List<U> mappedContent = domainPage.getContent().stream().map(mapper).toList();
        return PageResponseDto.<U>builder()
                .content(mappedContent)
                .page(domainPage.getPage())
                .size(domainPage.getSize())
                .totalElements(includeTotalCount ? domainPage.getTotalElements() : null)
                .totalPages(includeTotalCount ? domainPage.getTotalPages() : null)
                .build();
    }

    public static <T> PageResponseDto<T> from(Page<T> domainPage) {
        return from(domainPage, true);
    }

    public static <T> PageResponseDto<T> from(Page<T> domainPage, boolean includeTotalCount) {
        return PageResponseDto.<T>builder()
                .content(domainPage.getContent())
                .page(domainPage.getPage())
                .size(domainPage.getSize())
                .totalElements(includeTotalCount ? domainPage.getTotalElements() : null)
                .totalPages(includeTotalCount ? domainPage.getTotalPages() : null)
                .build();
    }
}
