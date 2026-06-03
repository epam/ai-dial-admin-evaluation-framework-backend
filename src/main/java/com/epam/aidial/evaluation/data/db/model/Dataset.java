package com.epam.aidial.evaluation.data.db.model;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Dataset {

    private UUID id;
    private String name;
    private String description;
    private String testCaseSchema;
    private boolean valid;
    private String validationWarnings;
    private DatasetVisibility visibility;
    private Long version;
    private String createdBy;
    private Long createdAt;
    private Long updatedAt;
}
