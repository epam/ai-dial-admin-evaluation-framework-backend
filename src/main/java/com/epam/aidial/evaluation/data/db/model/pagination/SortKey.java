package com.epam.aidial.evaluation.data.db.model.pagination;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SortKey {

    private String field;

    @Builder.Default
    private PageRequest.SortDirection direction = PageRequest.SortDirection.ASC;
}
