package com.epam.aidial.evaluation.experimental.query.service.repository;

/**
 * Experimental {@link StructuredQueryRepository} for the complex {@code test_cases} entity (meta
 * datasource). Unlike the cache-backed entities, it is instance-aware: the query must carry a
 * {@code dataset_id} equality filter, which scopes the rows and types the flattened
 * {@code data::<field>} bindings.
 */
public interface TestCaseQueryRepository extends StructuredQueryRepository {}
