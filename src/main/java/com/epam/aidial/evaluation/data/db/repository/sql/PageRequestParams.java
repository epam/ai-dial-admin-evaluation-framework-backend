package com.epam.aidial.evaluation.data.db.repository.sql;

import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class PageRequestParams {

    PageRequest.SortDirection defaultSortDirection;
    String defaultSortColumn;
    Map<String, String> allowedSortColumns;
}
