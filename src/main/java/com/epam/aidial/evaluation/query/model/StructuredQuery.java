package com.epam.aidial.evaluation.query.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Top-level query envelope (§1). {@code entity} and {@code mode} are always required; the
 * remaining sections are constrained by {@code mode} (§2), but that coherence is enforced by the
 * future validation layer, not structurally here.
 */
public record StructuredQuery(
        String entity,

        @JsonDeserialize(using = FilterNodeDeserializer.class)
        FilterNode filter,

        @NotNull QueryMode mode,
        boolean distinct,
        List<OutputColumn> select,
        @JsonProperty("group_by") List<String> groupBy,

        @JsonDeserialize(using = FilterNodeDeserializer.class)
        FilterNode having,

        List<SortItem> sort,
        PageSpec page) {}
