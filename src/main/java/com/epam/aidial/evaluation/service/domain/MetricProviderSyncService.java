package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.client.metricprovider.MetricProviderClient;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricsDescriptionDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricsResponseDto;
import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.model.MetricDeclaration;
import com.epam.aidial.evaluation.data.db.model.MetricDeclarationVersion;
import com.epam.aidial.evaluation.data.db.repository.MetricDeclarationRepository;
import com.epam.aidial.evaluation.data.db.repository.MetricDeclarationVersionRepository;
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
        for (MetricsDescriptionDto dto : metrics) {
            upsertDeclarationAndVersion(providerId, dto);
        }
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
