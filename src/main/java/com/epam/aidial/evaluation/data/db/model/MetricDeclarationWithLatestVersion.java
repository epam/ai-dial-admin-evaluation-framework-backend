package com.epam.aidial.evaluation.data.db.model;

/**
 * A metric declaration together with its latest {@link MetricDeclarationVersion} (greatest
 * schema_version), as read by
 * {@code MetricDeclarationVersionRepository.findLatestPerMetricDeclaration()}.
 *
 * <p>Both components are non-null: the query reads FROM metric_declaration_versions and joins the
 * declaration onto it, so a declaration without any version row is absent from the result rather than
 * present with a null version.
 */
public record MetricDeclarationWithLatestVersion(
        MetricDeclaration declaration, MetricDeclarationVersion latestVersion) {}
