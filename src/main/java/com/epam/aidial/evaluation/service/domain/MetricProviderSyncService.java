package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.metricprovider.MetricProviderClient;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricsDescriptionDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricsResponseDto;
import com.epam.aidial.evaluation.data.db.model.MetricDeclaration;
import com.epam.aidial.evaluation.data.db.model.MetricDeclarationVersion;
import com.epam.aidial.evaluation.data.db.repository.MetricDeclarationRepository;
import com.epam.aidial.evaluation.data.db.repository.MetricDeclarationVersionRepository;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Syncs metric declarations from a metric provider (GET /metrics) into the meta database.
 * One transaction per provider; upserts declarations and creates new versions when schema or description change.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@LogExecution
public class MetricProviderSyncService {

    private final MetricProviderClient metricProviderClient;
    private final MetricDeclarationRepository metricDeclarationRepository;
    private final MetricDeclarationVersionRepository metricDeclarationVersionRepository;
    private final ObjectMapper objectMapper;

    /**
     * Fetches GET /metrics for the provider and upserts declarations and versions in one meta transaction.
     * On update, only description and display_name are changed on the declaration; id, provider_id, name, created_at_ms are preserved.
     */
    @Transactional("metaTransactionManager")
    public void syncOne(String providerId) {
        MetricsResponseDto response = metricProviderClient.getMetrics(providerId);
        List<MetricsDescriptionDto> metrics =
                response != null && response.getMetrics() != null ? response.getMetrics() : List.of();
        for (MetricsDescriptionDto dto : sortedByName(metrics)) {
            upsertDeclarationAndVersion(providerId, dto);
        }
    }

    /**
     * Orders the provider's metrics by name so that concurrent instances take their per-declaration waits
     * in the same sequence. {@link #syncOne(String)} is one transaction per provider, and each changed
     * metric first inserts a version row - which waits on the other transaction when both computed the
     * same schema_version - and then row-locks the declaration via updateMetadata; both waits last until
     * commit. In raw provider-response order two instances could therefore take those waits in opposite
     * order and die on an ABBA deadlock (40P01) instead of the clean 23505 the version-assignment race is
     * meant to surface. UNIQUE(provider_id, name) makes name a total order within a provider, and ordered
     * traversals of one total order cannot cycle even when the two instances see different changed
     * subsets.
     *
     * <p>The response list is immutable, hence a sorted copy. name is not validated anywhere in the
     * provider contract, hence nullsLast - which only keeps the comparator itself from throwing; a
     * null-named metric still fails downstream on the NOT NULL name column.
     */
    private static List<MetricsDescriptionDto> sortedByName(List<MetricsDescriptionDto> metrics) {
        return metrics.stream()
                .sorted(Comparator.comparing(
                        MetricsDescriptionDto::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private void upsertDeclarationAndVersion(String providerId, MetricsDescriptionDto dto) {
        String name = dto.getName();
        String displayName = dto.getDisplayName();
        String description = normalizeDescription(dto.getDescription());
        String configSchema = dto.getConfigSchema();
        String inputSchema = dto.getInputSchema();
        String outputSchema = dto.getOutputSchema();

        Optional<MetricDeclaration> existing = metricDeclarationRepository.findByProviderIdAndName(providerId, name);
        if (existing.isEmpty()) {
            MetricDeclaration declaration = MetricDeclaration.builder()
                    .providerId(providerId)
                    .name(name)
                    .displayName(displayName)
                    .description(description)
                    .build();
            metricDeclarationRepository.save(declaration);
            saveVersion(declaration.getId(), 1, configSchema, inputSchema, outputSchema, displayName, description);
            log.debug("Synced new metric declaration: providerId={}, name={}", providerId, name);
            return;
        }

        MetricDeclaration declaration = existing.get();
        Optional<MetricDeclarationVersion> latest =
                metricDeclarationVersionRepository.findLatestByMetricDeclarationId(declaration.getId());
        boolean needsNewVersion = latest.isEmpty()
                || differsFromLatest(latest.get(), configSchema, inputSchema, outputSchema, displayName, description);
        if (needsNewVersion) {
            saveVersion(declaration.getId(), 0, configSchema, inputSchema, outputSchema, displayName, description);
            metricDeclarationRepository.updateMetadata(declaration.getId(), description, displayName);
            log.debug("Synced new version for metric declaration: providerId={}, name={}", providerId, name);
        }
    }

    private static boolean sameString(String a, String b) {
        return (a == null && b == null) || (a != null && a.equals(b));
    }

    private boolean differsFromLatest(
            MetricDeclarationVersion latest,
            String configSchema,
            String inputSchema,
            String outputSchema,
            String displayName,
            String description) {
        if (!sameString(latest.getDisplayName(), displayName)) {
            return true;
        }
        if (!sameString(latest.getDescription(), description)) {
            return true;
        }
        if (!jsonStructuralEquals(latest.getConfigSchema(), configSchema)) {
            return true;
        }
        if (!jsonStructuralEquals(latest.getInputSchema(), inputSchema)) {
            return true;
        }
        if (!jsonStructuralEquals(latest.getOutputSchema(), outputSchema)) {
            return true;
        }
        return false;
    }

    private boolean jsonStructuralEquals(String a, String b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        try {
            JsonNode na = objectMapper.readTree(a);
            JsonNode nb = objectMapper.readTree(b);
            return na != null && na.equals(nb);
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizeDescription(String description) {
        return description != null ? description : "";
    }

    private void saveVersion(
            UUID declarationId,
            int schemaVersion,
            String configSchema,
            String inputSchema,
            String outputSchema,
            String displayName,
            String description) {
        MetricDeclarationVersion version = MetricDeclarationVersion.builder()
                .metricDeclarationId(declarationId)
                .schemaVersion(schemaVersion)
                .configSchema(configSchema != null ? configSchema : "{}")
                .inputSchema(inputSchema != null ? inputSchema : "{}")
                .outputSchema(outputSchema != null ? outputSchema : "{}")
                .displayName(displayName)
                .description(normalizeDescription(description))
                .build();
        metricDeclarationVersionRepository.save(version);
    }
}
