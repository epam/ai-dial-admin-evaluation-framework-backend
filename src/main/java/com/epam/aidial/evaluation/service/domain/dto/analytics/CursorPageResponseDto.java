package com.epam.aidial.evaluation.service.domain.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CursorPageResponseDto<T> {

    private List<T> content;
    private int size;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String nextCursor;

    private boolean hasMore;
}
