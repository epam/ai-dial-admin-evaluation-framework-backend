package com.epam.aidial.evaluation.experimental.query.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The pagination request contract (§7): one shape, two client-selectable strategies discriminated
 * by {@code type} — {@link OffsetPage} (deterministic page numbers) and {@link CursorPage} (stable
 * keyset iteration).
 *
 * <p>Named {@code PageSpec} (not {@code Page}, as in the spec) to avoid colliding with the legacy
 * {@code data.db.model.pagination.Page}/{@code PageRequest} types.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = OffsetPage.class, name = "offset"),
    @JsonSubTypes.Type(value = CursorPage.class, name = "cursor")
})
public sealed interface PageSpec permits OffsetPage, CursorPage {}
