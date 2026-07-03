package com.epam.aidial.evaluation.experimental.query.model;

/**
 * One ordering key (§6): a projectable field / {@code group_by} key (row mode) or an aggregate
 * alias (aggregate mode), with a direction and optional null ordering. {@code nulls} is
 * client-controlled (§6, D8): when {@code null} the database default applies ({@code ASC} → NULLS
 * LAST, {@code DESC} → NULLS FIRST).
 */
public record SortItem(String field, SortDir dir, NullsOrder nulls) {

    // TODO(D5): when the client's sort is not a total order, the server appends a stable unique
    // tiebreaker (default: primary key) — a translation-stage concern, not part of the request shape.
}
