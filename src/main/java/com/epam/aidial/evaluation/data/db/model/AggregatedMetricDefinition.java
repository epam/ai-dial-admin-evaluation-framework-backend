package com.epam.aidial.evaluation.data.db.model;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Carrier for the 3-way JOIN result:
 * test_suite_metric_definitions + metric_declarations + metric_declaration_versions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AggregatedMetricDefinition {

    // -- test_suite_metric_definitions fields --
    private UUID id;
    private UUID testSuiteId;
    private UUID metricDeclarationId;
    private UUID metricDeclarationVersionId;
    private String name;
    /** JSONB array of parameter bindings for metric config. */
    private String configBindings;
    /** JSONB array of parameter bindings for metric input. */
    private String inputBindings;

    private boolean enabled;

    /** Optional JSONata condition gating whether this metric runs per result row (per turn). */
    private String condition;

    private boolean valid;
    /** JSONB array of ValidationWarningDto; stored as-is. */
    private String validationWarnings;

    private Long createdAt;
    private Long updatedAt;

    // -- metric_declarations fields --
    private String metricDeclarationName;
    private String declarationProviderId;
    private String declarationDescription;
    private Long declarationCreatedAt;

    // -- metric_declaration_versions fields --
    private UUID versionId;
    private int versionSchemaVersion;
    /** JSON schema; stored as JSONB in DB. */
    private String versionConfigSchema;
    /** JSON schema; stored as JSONB in DB. */
    private String versionInputSchema;
    /** JSON schema; stored as JSONB in DB. */
    private String versionOutputSchema;

    private String versionDescription;
    private Long versionCreatedAt;
}
