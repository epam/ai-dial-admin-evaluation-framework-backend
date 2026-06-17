package com.epam.aidial.evaluation.experimental.query.model;

/**
 * One ordering key (§6): a projectable field / {@code group_by} key (row mode) or an aggregate
 * alias (aggregate mode), with a direction.
 */
public record SortItem(String field, SortDir dir) {

    // TODO(D5): when the client's sort is not a total order, the server appends a stable unique
    // tiebreaker (default: primary key). TODO(D8): default null ordering (NULLS FIRST/LAST). Both
    // are translation-stage concerns, not part of the request shape.
}
