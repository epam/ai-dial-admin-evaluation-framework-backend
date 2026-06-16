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
public class MetricDeclarationVersion {

    private UUID id;
    private UUID metricDeclarationId;
    private int schemaVersion;
    /** JSON schema; stored as JSONB in DB. */
    private String configSchema;
    /** JSON schema; stored as JSONB in DB. */
    private String inputSchema;
    /** JSON schema; stored as JSONB in DB. */
    private String outputSchema;

    private String displayName;
    private String description;
    private Long createdAt;
}
