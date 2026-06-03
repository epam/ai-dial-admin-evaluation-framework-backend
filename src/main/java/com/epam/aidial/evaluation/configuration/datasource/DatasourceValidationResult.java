package com.epam.aidial.evaluation.configuration.datasource;

/**
 * Marker bean that indicates all datasource validations passed.
 * Both Flyway bean methods declare this as a parameter to ensure
 * validation completes before migration.
 */
public record DatasourceValidationResult(boolean validated) {}
