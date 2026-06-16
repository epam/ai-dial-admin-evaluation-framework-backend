package com.epam.aidial.evaluation.data.db.analytics.repository;

import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.Cursor;
import com.epam.aidial.evaluation.data.db.analytics.model.cursor.CursorPage;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestCaseRunResultRepository {

    void saveAll(List<TestCaseRunResult> results);

    CursorPage<TestCaseRunResult> findAll(List<FilterCondition> filters, Long runCreatedAtMs, Cursor cursor, int size);

    Optional<TestCaseRunResult> findById(UUID id);

    long count(List<FilterCondition> filters, Long runCreatedAtMs);
}
