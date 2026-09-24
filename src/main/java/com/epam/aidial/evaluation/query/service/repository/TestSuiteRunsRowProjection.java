package com.epam.aidial.evaluation.query.service.repository;

import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.OutputColumn;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Query-level projection checks shared by the {@code test_suite_runs} {@link QueryResultPageExtender}s. A
 * {@code row} result's key set is fixed by its projection, so each check decides for every row at once.
 */
final class TestSuiteRunsRowProjection {

    static final String ID_FIELD = "id";

    private TestSuiteRunsRowProjection() {}

    /**
     * Whether every row carries the run's own {@code id} under the {@code id} key: an empty projection
     * selects every entity field, otherwise some column must be the plain {@code id} field keyed as
     * {@code id}. A key match alone is not enough — another expression aliased as {@code id} would key
     * the lookup on the wrong value.
     */
    static boolean projectsRunId(List<OutputColumn> select) {
        if (select == null || select.isEmpty()) {
            return true;
        }
        return select.stream()
                .anyMatch(col -> col.expr() instanceof FieldExpr(String fieldName)
                        && ID_FIELD.equals(fieldName)
                        && ID_FIELD.equals(col.outputKey()));
    }

    static boolean doesNotProjectKey(List<OutputColumn> select, String key) {
        return select == null || select.stream().noneMatch(col -> key.equals(col.outputKey()));
    }

    /** The run's primary key; only valid for a page whose query passed {@link #projectsRunId}. */
    static UUID runId(Map<String, Object> row) {
        return UUID.fromString((String) row.get(ID_FIELD));
    }
}
