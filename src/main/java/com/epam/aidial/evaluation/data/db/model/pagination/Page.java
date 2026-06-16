package com.epam.aidial.evaluation.data.db.model.pagination;

import java.util.Collections;
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
public class Page<T> {

    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean hasNext;
    private boolean hasPrevious;

    public static <T> Page<T> of(List<T> content, PageRequest pageRequest, long totalElements) {
        int totalPages = (int) Math.ceil((double) totalElements / pageRequest.getValidatedSize());
        return Page.<T>builder()
                .content(content)
                .page(pageRequest.getPage())
                .size(pageRequest.getValidatedSize())
                .totalElements(totalElements)
                .totalPages(totalPages)
                .hasNext(pageRequest.getPage() < totalPages - 1)
                .hasPrevious(pageRequest.getPage() > 0)
                .build();
    }

    public static <T> Page<T> withoutTotal(List<T> content, PageRequest pageRequest) {
        int size = pageRequest.getValidatedSize();
        return Page.<T>builder()
                .content(content)
                .page(pageRequest.getPage())
                .size(size)
                .totalElements(-1)
                .totalPages(-1)
                .hasNext(content.size() == size)
                .hasPrevious(pageRequest.getPage() > 0)
                .build();
    }

    public static <T> Page<T> empty(PageRequest pageRequest) {
        return Page.<T>builder()
                .content(Collections.emptyList())
                .page(pageRequest.getPage())
                .size(pageRequest.getValidatedSize())
                .totalElements(0)
                .totalPages(0)
                .hasNext(false)
                .hasPrevious(false)
                .build();
    }

    public <U> Page<U> map(Function<T, U> mapper) {
        List<U> mappedContent = content.stream().map(mapper).toList();
        return Page.<U>builder()
                .content(mappedContent)
                .page(this.page)
                .size(this.size)
                .totalElements(this.totalElements)
                .totalPages(this.totalPages)
                .hasNext(this.hasNext)
                .hasPrevious(this.hasPrevious)
                .build();
    }
}
