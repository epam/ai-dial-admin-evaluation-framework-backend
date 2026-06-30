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
public class MetricScoreDefinition {
    private UUID id;
    private MetricScoreDefinitionType type;
    private String name;
    private String description;
    private String expression;
    private UUID targetId;
}
