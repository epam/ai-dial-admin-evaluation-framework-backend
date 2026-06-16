package com.epam.aidial.evaluation.data.db.model.filter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilterCondition {

    private String field;
    private FilterOperator operator;
    private String rawValue;
    private Object parsedValue;
}
