package com.epam.aidial.evaluation.data.db.model;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Test case entity for revalidation and CRUD.
 * data stored as JSON string (from jsonb).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestCase {

    private UUID id;
    private UUID datasetId;
    private String testCaseName;
    private String data;

    /**
     * JSON array of turn-data maps for a multi-turn test case (stored as jsonb). Null for a single-turn
     * case. Mutually exclusive with a populated {@code data} (multi-turn ⇒ {@code data = '{}'}).
     */
    private String multiTurnData;

    private boolean valid;
    /** JSON array of ValidationWarningDto; stored as jsonb in DB. */
    private String validationWarnings;

    private Long createdAt;
    private Long updatedAt;
}
