package com.epam.aidial.evaluation.experimental.query.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Top-level query envelope (§1). Only {@code entity} is always required; the remaining sections
 * are constrained by {@code mode} (§2), but that coherence is enforced by the future validation
 * layer, not structurally here.
 */
public record StructuredQuery(
        String entity,

        @JsonDeserialize(using = FilterNodeDeserializer.class)
        FilterNode filter,

        QueryMode mode,
        boolean distinct,
        List<OutputColumn> select,
        @JsonProperty("group_by") List<String> groupBy,

        @JsonDeserialize(using = FilterNodeDeserializer.class)
        FilterNode having,

        List<SortItem> sort,
        PageSpec page) {

    // TODO(D1): mode is explicit per spec; a later revision could infer it from the presence of
    // group_by/select aggregate functions instead of requiring the client to send it.
}
