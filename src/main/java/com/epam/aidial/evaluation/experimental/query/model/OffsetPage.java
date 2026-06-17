package com.epam.aidial.evaluation.experimental.query.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Offset pagination (§7.1): a 0-based row {@code offset} and {@code limit}, with opt-in total count
 * via {@code include_total} (default false). {@code limit}/max-offset governance (§7.5) is applied
 * by the execution layer, not modeled here.
 */
public record OffsetPage(
        long offset,
        int limit,
        @JsonProperty("include_total") boolean includeTotal) implements PageSpec {}
