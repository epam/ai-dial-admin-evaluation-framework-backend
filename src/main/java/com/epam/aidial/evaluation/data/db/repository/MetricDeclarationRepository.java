package com.epam.aidial.evaluation.data.db.repository;

import com.epam.aidial.evaluation.data.db.model.MetricDeclaration;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetricDeclarationRepository {

    Page<MetricDeclaration> findAll(PageRequest pageRequest);

    Page<MetricDeclaration> findAll(PageRequest pageRequest, boolean includeTotalCount);

    Page<MetricDeclaration> findAll(PageRequest pageRequest, List<FilterCondition> filters, boolean includeTotalCount);

    Optional<MetricDeclaration> findById(UUID id);

    Optional<MetricDeclaration> findByProviderIdAndName(String providerId, String name);

    /**
     * Inserts a new metric declaration. Id and createdAt are set from context if null.
     */
    MetricDeclaration save(MetricDeclaration declaration);

    /**
     * Atomically updates description and displayName of an existing declaration.
     */
    void updateMetadata(UUID id, String description, String displayName);
}
