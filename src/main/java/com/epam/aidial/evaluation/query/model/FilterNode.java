package com.epam.aidial.evaluation.query.model;

/**
 * A node in the recursive filter tree (§3, CQL2-JSON shape). The {@code op} key is both the subtype
 * discriminator and operator data, so it cannot be expressed with {@code @JsonTypeInfo} (which would
 * write a second, ambiguous {@code op}); routing is done by {@link FilterNodeDeserializer}, wired at
 * each use site ({@code StructuredQuery.filter}/{@code having} via {@code using}, {@code
 * LogicalNode.args} via {@code contentUsing}) rather than on this interface — an annotation here
 * would be inherited by the subtypes and recurse. Serialization is automatic: each record writes its
 * own single {@code op} (lowercase via the enum's {@code @JsonValue}) plus {@code args}.
 */
public sealed interface FilterNode permits LogicalNode, ComparisonNode {}
