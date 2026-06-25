package com.epam.aidial.evaluation.experimental.query.service;

import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
import org.jooq.Field;

/**
 * Binds a flat API field name (as published by the schema endpoint, e.g. {@code createdAt},
 * {@code valid}) to the generated jOOQ {@link Field} that backs it and its declared
 * {@link QueryFieldType}. Produced by {@link JooqTableSchemaResolver#bindings} and consumed by the
 * model → jOOQ translation layer so that field resolution stays single-sourced with the schema the
 * client discovers.
 */
public record QueryFieldBinding(String name, Field<?> field, QueryFieldType type) {}
