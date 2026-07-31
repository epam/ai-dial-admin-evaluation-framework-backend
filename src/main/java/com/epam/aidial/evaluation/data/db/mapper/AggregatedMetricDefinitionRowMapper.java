package com.epam.aidial.evaluation.data.db.mapper;

import com.epam.aidial.evaluation.data.db.model.AggregatedMetricDefinition;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class AggregatedMetricDefinitionRowMapper implements RowMapper<AggregatedMetricDefinition> {

    @Override
    @NotNull
    public AggregatedMetricDefinition mapRow(ResultSet rs, int rowNum) throws SQLException {
        return AggregatedMetricDefinition.builder()
                // metric definition
                .id(UUID.fromString(rs.getString("id")))
                .testSuiteId(UUID.fromString(rs.getString("test_suite_id")))
                .metricDeclarationId(UUID.fromString(rs.getString("metric_declaration_id")))
                .metricDeclarationVersionId(UUID.fromString(rs.getString("metric_declaration_version_id")))
                .name(rs.getString("name"))
                .configBindings(rs.getString("config_bindings"))
                .inputBindings(rs.getString("input_bindings"))
                .enabled(rs.getBoolean("is_enabled"))
                .condition(rs.getString("condition"))
                .valid(rs.getBoolean("is_valid"))
                .validationWarnings(rs.getString("validation_warnings"))
                .createdAt(rs.getLong("created_at_ms"))
                .updatedAt(rs.getLong("updated_at_ms"))
                // metric declaration
                .metricDeclarationName(rs.getString("metric_declaration_name"))
                .declarationProviderId(rs.getString("provider_id"))
                .declarationDescription(rs.getString("declaration_description"))
                .declarationCreatedAt(rs.getLong("declaration_created_at_ms"))
                // metric declaration version
                .versionId(UUID.fromString(rs.getString("version_id")))
                .versionSchemaVersion(rs.getInt("schema_version"))
                .versionConfigSchema(rs.getString("config_schema"))
                .versionInputSchema(rs.getString("input_schema"))
                .versionOutputSchema(rs.getString("output_schema"))
                .versionDescription(rs.getString("version_description"))
                .versionCreatedAt(rs.getLong("version_created_at_ms"))
                .build();
    }
}
