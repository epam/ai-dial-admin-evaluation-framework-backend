package com.epam.aidial.evaluation.data.db.mapper;

import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.MetricDeclarationVersionsRecord;
import com.epam.aidial.evaluation.data.db.model.MetricDeclarationVersion;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.UUID;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class MetricDeclarationVersionRecordMapper {

    public MetricDeclarationVersion map(MetricDeclarationVersionsRecord r) {
        return MetricDeclarationVersion.builder()
                .id(UUID.fromString(r.getId()))
                .metricDeclarationId(UUID.fromString(r.getMetricDeclarationId()))
                .schemaVersion(r.getSchemaVersion())
                .configSchema(toJsonString(r.getConfigSchema()))
                .inputSchema(toJsonString(r.getInputSchema()))
                .outputSchema(toJsonString(r.getOutputSchema()))
                .displayName(r.getDisplayName())
                .description(r.getDescription())
                .createdAt(r.getCreatedAtMs())
                .build();
    }

    private static String toJsonString(JSONB jsonb) {
        return jsonb != null ? jsonb.data() : null;
    }
}
