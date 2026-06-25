package com.epam.aidial.evaluation.data.db.analytics.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreDefinition;
import com.epam.aidial.evaluation.data.db.jooq.analytics.tables.records.MetricScoreDefinitionRecord;
import java.util.UUID;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class MetricScoreDefinitionRecordMapper {

    public MetricScoreDefinition map(MetricScoreDefinitionRecord r) {
        return MetricScoreDefinition.builder()
                .id(UUID.fromString(r.getId()))
                .type(r.getType())
                .name(r.getName())
                .description(r.getDescription())
                .expression(toJsonString(r.getExpression()))
                .targetId(r.getTargetId() != null ? UUID.fromString(r.getTargetId()) : null)
                .build();
    }

    private static String toJsonString(JSONB jsonb) {
        return jsonb != null ? jsonb.data() : null;
    }
}
