package com.epam.aidial.evaluation.service.domain.dto.page;

import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.runner.dto.PageResponseDto;
import java.util.List;
import java.util.function.Function;
import lombok.experimental.UtilityClass;

@UtilityClass
public class PageResponseMapper {

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
