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
public class TestSuiteMetricDefinition {

    private UUID id;
    private UUID testSuiteId;
    private UUID metricDeclarationId;
    private UUID metricDeclarationVersionId;
    private String name;
    /** JSONB array of parameter bindings for metric config. */
    private String configBindings;
    /** JSONB array of parameter bindings for metric input. */
    private String inputBindings;
    /**
     * Optional execution condition. Null/blank ⇒ metric always runs. Otherwise a bare {@code name()}
     * custom-function call or a JSONata expression, evaluated per test-case result.
     */
    private String condition;

    private boolean enabled;
    private boolean valid;
    /** JSONB array of ValidationWarningDto; stored as-is. */
    private String validationWarnings;
    /** Transient — populated from JOIN with metric_declarations, not persisted. */
    private String metricDeclarationName;

    private Long createdAt;
    private Long updatedAt;
}
