package com.epam.aidial.evaluation.cli.client.source.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Local mirror of the EF backend's {@code PageResponseDto}.
 *
 * <p>Manually kept in sync with
 * {@code com.epam.aidial.evaluation.service.domain.dto.page.PageResponseDto}.
 *
 * @param <T> the element type
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponseDto<T> {

    private List<T> content;
    private int page;
    private int size;
    private Long totalElements;
    private Integer totalPages;
}
