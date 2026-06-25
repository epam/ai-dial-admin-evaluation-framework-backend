package com.epam.aidial.evaluation.experimental.query.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A literal (§4.1/§4.2): {@code value} is <strong>always</strong> a JSON string and {@code
 * value_type} governs how it is parsed into a typed value (in the out-of-scope parse stage). For
 * {@code value_type == null}, {@code value} is ignored.
 */
public record ValueExpr(@JsonProperty("value_type") ValueType valueType, String value) implements Expr {}
