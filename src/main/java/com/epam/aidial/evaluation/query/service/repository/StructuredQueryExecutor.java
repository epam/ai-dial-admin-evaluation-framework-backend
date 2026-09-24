package com.epam.aidial.evaluation.query.service.repository;

import com.epam.aidial.evaluation.query.model.StructuredQuery;

public interface StructuredQueryExecutor {
    QueryResultPage execute(StructuredQuery rawQuery);
}
