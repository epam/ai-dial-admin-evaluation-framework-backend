package com.epam.aidial.evaluation.query.model;

/**
 * Cursor pagination (§7.1): a server-issued opaque {@code cursor} echoed verbatim by the client
 * (null/omitted on the first page) and a {@code limit}. The cursor's encoding and staleness
 * behavior are implementation details outside this model.
 */
public record CursorPage(String cursor, int limit) implements PageSpec {}
