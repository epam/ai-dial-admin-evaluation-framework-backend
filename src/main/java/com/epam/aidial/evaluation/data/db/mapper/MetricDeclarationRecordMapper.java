package com.epam.aidial.evaluation.data.db.mapper;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.jooq.meta.tables.records.MetricDeclarationsRecord;
import com.epam.aidial.evaluation.data.db.model.MetricDeclaration;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
@LogExecution
public class MetricDeclarationRecordMapper {

    public MetricDeclaration map(MetricDeclarationsRecord r) {
        return MetricDeclaration.builder()
                .id(UUID.fromString(r.getId()))
                .providerId(r.getProviderId())
                .name(r.getName())
                .displayName(r.getDisplayName())
                .description(r.getDescription())
                .createdAt(r.getCreatedAtMs())
                .build();
    }
}
