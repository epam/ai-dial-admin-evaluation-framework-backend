package com.epam.aidial.evaluation.client.dialadas.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.node.ObjectNode;

/**
 * Request body for dial-adas {@code POST /v1/queries/execute} in {@code "mode": "aggregate"}.
 * {@code filter} and each {@code select} entry are heterogeneous op/fn/field/value trees, modeled as
 * {@link ObjectNode} rather than a full typed AST since this is one fixed, code-controlled query shape.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdasAggregateQueryDto {

    private String entity;

    private String mode;

    private ObjectNode filter;

    @JsonProperty("group_by")
    private List<Object> groupBy;

    private List<ObjectNode> select;
}
