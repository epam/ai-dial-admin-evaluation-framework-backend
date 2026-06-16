package com.epam.aidial.evaluation.data.db.repository;

import com.epam.aidial.evaluation.data.db.model.MetricDeclarationVersion;
import java.util.Optional;
import java.util.UUID;

public interface MetricDeclarationVersionRepository {

    MetricDeclarationVersion save(MetricDeclarationVersion version);

    /**
     * Returns the latest version for the given metric declaration (by schema_version descending).
     */
    Optional<MetricDeclarationVersion> findLatestByMetricDeclarationId(UUID metricDeclarationId);

    boolean existsByIdAndMetricDeclarationId(UUID id, UUID metricDeclarationId);

    Optional<MetricDeclarationVersion> findByIdAndMetricDeclarationId(UUID id, UUID metricDeclarationId);
}
