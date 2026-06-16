package com.epam.aidial.evaluation.data.db.repository.sql;

import java.util.Map;
import java.util.Set;

/**
 * Single canonical mapping from API-field name to SQL column for the test-case bulk-patch whitelist.
 * The key set IS the bulk-patch whitelist; both the service-layer validator and the data-layer
 * repository read from this same source so drift is structurally impossible.
 */
public final class BulkPatchFields {

    public static final Map<String, String> BULK_PATCH_FIELD_TO_COLUMN = Map.of(
            "testCaseName", "test_case_name",
            "data", "data");

    public static Set<String> allowedFields() {
        return BULK_PATCH_FIELD_TO_COLUMN.keySet();
    }

    public static String columnFor(String apiField) {
        return BULK_PATCH_FIELD_TO_COLUMN.get(apiField);
    }

    private BulkPatchFields() {}
}
