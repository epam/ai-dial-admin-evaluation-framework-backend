package com.epam.aidial.evaluation.data.db.model.pagination;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageRequest {

    @Builder.Default
    private int page = 0;

    @Builder.Default
    private int size = 0;

    /**
     * Structured (multi-key) sort specification, in precedence order.
     * When empty, {@link #sortBy}/{@link #sortDirection} (and defaults) apply.
     */
    @Builder.Default
    private List<SortKey> sort = List.of();

    private String sortBy;

    @Builder.Default
    private SortDirection sortDirection = SortDirection.ASC;

    public int getOffset() {
        validatePage(page);
        int validatedSize = getValidatedSize();

        long offset = (long) page * validatedSize;
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Offset is too large: " + offset);
        }
        return (int) offset;
    }

    public int getValidatedSize() {
        validateSize(size);
        return size;
    }

    public enum SortDirection {
        ASC,
        DESC
    }

    public static PageRequest of(int page, int size) {
        return PageRequest.builder().page(page).size(size).build();
    }

    public static PageRequest of(int page, int size, String sortBy, SortDirection sortDirection) {
        return PageRequest.builder()
                .page(page)
                .size(size)
                .sortBy(sortBy)
                .sortDirection(sortDirection)
                .build();
    }

    public static PageRequest of(int page, int size, List<SortKey> sort) {
        return PageRequest.builder()
                .page(page)
                .size(size)
                .sort(sort != null ? List.copyOf(sort) : List.of())
                .build();
    }

    private static void validatePage(int page) {
        if (page < 0) {
            throw new IllegalArgumentException("Page must be >= 0, but was: " + page);
        }
    }

    private static void validateSize(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("Size must be >= 1, but was: " + size);
        }
    }
}
