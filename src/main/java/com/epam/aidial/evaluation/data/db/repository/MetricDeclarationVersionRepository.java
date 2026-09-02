package com.epam.aidial.evaluation.data.db.repository;

import com.epam.aidial.evaluation.data.db.model.MetricDeclarationVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetricDeclarationVersionRepository {

    MetricDeclarationVersion save(MetricDeclarationVersion version);

    /**
     * Returns the latest version for the given metric declaration (by schema_version descending).
     */
    Optional<MetricDeclarationVersion> findLatestByMetricDeclarationId(UUID metricDeclarationId);

    /**
     * Returns the latest version of every metric declaration: one row per metric_declaration_id, the one
     * with the greatest schema_version. Declarations without any version are absent from the result.
     * Ordered by metric_declaration_id.
     */
    List<MetricDeclarationVersion> findLatestPerMetricDeclaration();

    boolean existsByIdAndMetricDeclarationId(UUID id, UUID metricDeclarationId);

    Optional<MetricDeclarationVersion> findByIdAndMetricDeclarationId(UUID id, UUID metricDeclarationId);
}
