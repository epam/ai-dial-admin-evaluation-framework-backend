package com.epam.aidial.evaluation.query.model;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * A single {@code WHEN <when> THEN <then>} clause of a {@link CaseExpr}. {@code when} is a {@link
 * FilterNode}, deserialized via {@link FilterNodeDeserializer} directly on this field — {@code
 * FilterNodeDeserializer} is wired per use site rather than on {@link FilterNode} itself (see its
 * javadoc), and this is a new such site.
 */
public record WhenClause(
        @JsonDeserialize(using = FilterNodeDeserializer.class)
        FilterNode when,

        Expr then) {}
