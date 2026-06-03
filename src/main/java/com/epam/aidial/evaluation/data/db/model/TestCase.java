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
    private boolean valid;
    /** JSON array of ValidationWarningDto; stored as jsonb in DB. */
    private String validationWarnings;

    private Long createdAt;
    private Long updatedAt;
}
