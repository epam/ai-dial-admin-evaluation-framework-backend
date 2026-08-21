package com.epam.aidial.evaluation.query.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * The uniform expression grammar (§4), used wherever a value can appear ({@code select} entries,
 * function arguments, filter/{@code having} operands, aggregate arguments). Discriminated by
 * {@code type}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = FieldExpr.class, name = "field"),
    @JsonSubTypes.Type(value = ValueExpr.class, name = "value"),
    @JsonSubTypes.Type(value = ParamExpr.class, name = "param"),
    @JsonSubTypes.Type(value = FnExpr.class, name = "fn"),
    @JsonSubTypes.Type(value = ArrayExpr.class, name = "array"),
    @JsonSubTypes.Type(value = SubqueryExpr.class, name = "subquery")
})
public sealed interface Expr permits FieldExpr, ValueExpr, ParamExpr, FnExpr, ArrayExpr, SubqueryExpr {}
