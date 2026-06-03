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
public class MetricDeclaration {

    private UUID id;
    private String providerId;
    private String name;
    private String displayName;
    private String description;
    private Long createdAt;
}
