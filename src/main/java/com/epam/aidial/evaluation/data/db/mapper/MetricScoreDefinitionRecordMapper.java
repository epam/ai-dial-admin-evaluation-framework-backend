package com.epam.aidial.evaluation.data.db.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.MetricScoreDefinitionRecord;
import com.epam.aidial.evaluation.data.db.model.MetricScoreDefinition;
import com.epam.aidial.evaluation.data.db.model.MetricScoreDefinitionType;
import java.util.UUID;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class MetricScoreDefinitionRecordMapper {

    public MetricScoreDefinition map(MetricScoreDefinitionRecord r) {
        return MetricScoreDefinition.builder()
                .id(UUID.fromString(r.getId()))
                .type(MetricScoreDefinitionType.fromValue(r.getType()))
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
