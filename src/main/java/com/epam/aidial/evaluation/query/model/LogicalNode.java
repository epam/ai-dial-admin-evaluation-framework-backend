package com.epam.aidial.evaluation.query.model;

import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Logical combinator (§3): {@code and}/{@code or} over N children, {@code not} over one. The
 * {@code not}-arity-1 rule is a validation concern and is not enforced structurally here.
 */
public record LogicalNode(
        LogicalOp op,

        @JsonDeserialize(contentUsing = FilterNodeDeserializer.class)
        List<FilterNode> args) implements FilterNode {}
