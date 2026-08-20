package com.epam.aidial.evaluation.query.model;

import java.util.Locale;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Routes a filter node by its {@code op} (§3): {@code and}/{@code or}/{@code not} → {@link
 * LogicalNode}, any other code → {@link ComparisonNode}. Wired at use sites (not on {@link
 * FilterNode}) so deserializing the concrete subtypes here does not re-enter this deserializer.
 */
public class FilterNodeDeserializer extends ValueDeserializer<FilterNode> {

    private static final Set<String> LOGICAL_OPS = Set.of("and", "or", "not");

    @Override
    public FilterNode deserialize(JsonParser p, DeserializationContext ctxt) {
        final JsonNode node = ctxt.readTree(p);
        final JsonNode opNode = node.get("op");
        final String op = opNode == null ? null : opNode.asString();
        if (op != null && LOGICAL_OPS.contains(op.toLowerCase(Locale.ROOT))) {
            return ctxt.readTreeAsValue(node, LogicalNode.class);
        }
        return ctxt.readTreeAsValue(node, ComparisonNode.class);
    }
}
