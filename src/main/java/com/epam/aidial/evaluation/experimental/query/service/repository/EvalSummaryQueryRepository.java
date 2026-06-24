package com.epam.aidial.evaluation.experimental.query.service.repository;

/**
 * Experimental {@link StructuredQueryRepository} for the {@code eval_summaries} entity (analytics
 * datasource, table {@code test_case_eval_summaries}). Queries the flat base-schema columns; the
 * detailed {@code data::}/{@code response::}/{@code metric::} flattening published by the schema
 * discovery API is not yet translatable and is a follow-up.
 */
public interface EvalSummaryQueryRepository extends StructuredQueryRepository {}
