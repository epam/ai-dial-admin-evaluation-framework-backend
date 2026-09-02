package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.metricprovider.MetricProviderClient;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricsDescriptionDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricsResponseDto;
import com.epam.aidial.evaluation.data.db.model.MetricDeclaration;
import com.epam.aidial.evaluation.data.db.model.MetricDeclarationVersion;
import com.epam.aidial.evaluation.data.db.repository.MetricDeclarationRepository;
import com.epam.aidial.evaluation.data.db.repository.MetricDeclarationVersionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("MetricProviderSyncService")
class MetricProviderSyncServiceTest {

    private static final String PROVIDER_ID = "test-provider";
    private static final UUID DECLARATION_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Mock
    private MetricProviderClient metricProviderClient;

    @Mock
    private MetricDeclarationRepository metricDeclarationRepository;

    @Mock
    private MetricDeclarationVersionRepository metricDeclarationVersionRepository;

    private MetricProviderSyncService syncService;

    @BeforeEach
    void setUp() {
        syncService = new MetricProviderSyncService(
                metricProviderClient,
                metricDeclarationRepository,
                metricDeclarationVersionRepository,
                new ObjectMapper());
    }

    @Nested
    @DisplayName("metric iteration order")
    class MetricIterationOrder {

        @Test
        @DisplayName("processes a provider's metrics sorted by name, not in provider-response order")
        void unsortedResponse_processedInNameOrder() {
            when(metricProviderClient.getMetrics(PROVIDER_ID))
                    .thenReturn(MetricsResponseDto.builder()
                            .metrics(List.of(metric("Zeta"), metric("Alpha")))
                            .build());
            when(metricDeclarationRepository.findByProviderIdAndName(eq(PROVIDER_ID), any()))
                    .thenReturn(Optional.empty());

            syncService.syncOne(PROVIDER_ID);

            final var order = inOrder(metricDeclarationRepository);
            order.verify(metricDeclarationRepository).findByProviderIdAndName(PROVIDER_ID, "Alpha");
            order.verify(metricDeclarationRepository).findByProviderIdAndName(PROVIDER_ID, "Zeta");
        }

        @Test
        @DisplayName("sorts a metric with no name last instead of failing the provider's sync")
        void nullNamedMetric_sortedLastWithoutThrowing() {
            when(metricProviderClient.getMetrics(PROVIDER_ID))
                    .thenReturn(MetricsResponseDto.builder()
                            .metrics(List.of(metric(null), metric("Alpha")))
                            .build());
            when(metricDeclarationRepository.findByProviderIdAndName(eq(PROVIDER_ID), any()))
                    .thenReturn(Optional.empty());

            syncService.syncOne(PROVIDER_ID);

            final var order = inOrder(metricDeclarationRepository);
            order.verify(metricDeclarationRepository).findByProviderIdAndName(PROVIDER_ID, "Alpha");
            order.verify(metricDeclarationRepository).findByProviderIdAndName(PROVIDER_ID, null);
        }

        private MetricsDescriptionDto metric(String name) {
            return MetricsDescriptionDto.builder()
                    .name(name)
                    .displayName(name)
                    .description("d")
                    .configSchema("{}")
                    .inputSchema("{}")
                    .outputSchema("{}")
                    .build();
        }
    }

    @Nested
    @DisplayName("syncOne")
    class SyncOne {

        @Test
        @DisplayName("does nothing when provider returns empty metrics")
        void emptyMetrics_noSaves() {
            when(metricProviderClient.getMetrics(PROVIDER_ID))
                    .thenReturn(MetricsResponseDto.builder().metrics(List.of()).build());

            syncService.syncOne(PROVIDER_ID);

            verify(metricDeclarationRepository, never()).save(any());
            verify(metricDeclarationVersionRepository, never()).save(any());
        }

        @Test
        @DisplayName("saves new declaration and first version when declaration does not exist")
        void newMetric_savesDeclarationAndVersion() {
            when(metricProviderClient.getMetrics(PROVIDER_ID))
                    .thenReturn(MetricsResponseDto.builder()
                            .metrics(List.of(MetricsDescriptionDto.builder()
                                    .name("Accuracy")
                                    .displayName("Exact Match")
                                    .description("Correctness")
                                    .configSchema("{}")
                                    .inputSchema("{}")
                                    .outputSchema("{}")
                                    .build()))
                            .build());
            when(metricDeclarationRepository.findByProviderIdAndName(PROVIDER_ID, "Accuracy"))
                    .thenReturn(Optional.empty());
            when(metricDeclarationRepository.save(any())).thenAnswer(inv -> {
                MetricDeclaration d = inv.getArgument(0);
                if (d.getId() == null) {
                    d.setId(DECLARATION_ID);
                }
                return d;
            });

            syncService.syncOne(PROVIDER_ID);

            ArgumentCaptor<MetricDeclaration> declCaptor = ArgumentCaptor.forClass(MetricDeclaration.class);
            verify(metricDeclarationRepository).save(declCaptor.capture());
            assertThat(declCaptor.getValue().getProviderId()).isEqualTo(PROVIDER_ID);
            assertThat(declCaptor.getValue().getName()).isEqualTo("Accuracy");
            assertThat(declCaptor.getValue().getDisplayName()).isEqualTo("Exact Match");
            assertThat(declCaptor.getValue().getDescription()).isEqualTo("Correctness");

            ArgumentCaptor<MetricDeclarationVersion> versionCaptor =
                    ArgumentCaptor.forClass(MetricDeclarationVersion.class);
            verify(metricDeclarationVersionRepository).save(versionCaptor.capture());
            assertThat(versionCaptor.getValue().getMetricDeclarationId()).isEqualTo(DECLARATION_ID);
            assertThat(versionCaptor.getValue().getSchemaVersion()).isEqualTo(1);
            assertThat(versionCaptor.getValue().getDisplayName()).isEqualTo("Exact Match");
            assertThat(versionCaptor.getValue().getDescription()).isEqualTo("Correctness");
        }

        @Test
        @DisplayName("saves new declaration with null displayName when provider omits display_name")
        void newMetric_nullDisplayName_savesDeclarationWithNullDisplayName() {
            when(metricProviderClient.getMetrics(PROVIDER_ID))
                    .thenReturn(MetricsResponseDto.builder()
                            .metrics(List.of(MetricsDescriptionDto.builder()
                                    .name("Accuracy")
                                    .description("Correctness")
                                    .configSchema("{}")
                                    .inputSchema("{}")
                                    .outputSchema("{}")
                                    .build()))
                            .build());
            when(metricDeclarationRepository.findByProviderIdAndName(PROVIDER_ID, "Accuracy"))
                    .thenReturn(Optional.empty());
            when(metricDeclarationRepository.save(any())).thenAnswer(inv -> {
                MetricDeclaration d = inv.getArgument(0);
                if (d.getId() == null) {
                    d.setId(DECLARATION_ID);
                }
                return d;
            });

            syncService.syncOne(PROVIDER_ID);

            ArgumentCaptor<MetricDeclaration> declCaptor = ArgumentCaptor.forClass(MetricDeclaration.class);
            verify(metricDeclarationRepository).save(declCaptor.capture());
            assertThat(declCaptor.getValue().getDisplayName()).isNull();

            ArgumentCaptor<MetricDeclarationVersion> versionCaptor =
                    ArgumentCaptor.forClass(MetricDeclarationVersion.class);
            verify(metricDeclarationVersionRepository).save(versionCaptor.capture());
            assertThat(versionCaptor.getValue().getDisplayName()).isNull();
        }

        @Test
        @DisplayName(
                "does not create new version when existing declaration has same schema, description and displayName")
        void existingDeclarationSameSchema_noNewVersion() {
            MetricDeclaration existing = MetricDeclaration.builder()
                    .id(DECLARATION_ID)
                    .providerId(PROVIDER_ID)
                    .name("Accuracy")
                    .displayName("Exact Match")
                    .description("Correctness")
                    .build();
            MetricDeclarationVersion latestVersion = MetricDeclarationVersion.builder()
                    .configSchema("{}")
                    .inputSchema("{}")
                    .outputSchema("{}")
                    .displayName("Exact Match")
                    .description("Correctness")
                    .build();
            when(metricProviderClient.getMetrics(PROVIDER_ID))
                    .thenReturn(MetricsResponseDto.builder()
                            .metrics(List.of(MetricsDescriptionDto.builder()
                                    .name("Accuracy")
                                    .displayName("Exact Match")
                                    .description("Correctness")
                                    .configSchema("{}")
                                    .inputSchema("{}")
                                    .outputSchema("{}")
                                    .build()))
                            .build());
            when(metricDeclarationRepository.findByProviderIdAndName(PROVIDER_ID, "Accuracy"))
                    .thenReturn(Optional.of(existing));
            when(metricDeclarationVersionRepository.findLatestByMetricDeclarationId(DECLARATION_ID))
                    .thenReturn(Optional.of(latestVersion));

            syncService.syncOne(PROVIDER_ID);

            verify(metricDeclarationRepository, never()).save(any());
            verify(metricDeclarationRepository, never()).updateMetadata(any(), any(), any());
            verify(metricDeclarationVersionRepository, never()).save(any());
        }

        @Test
        @DisplayName("creates new version and updates metadata when displayName differs")
        void existingDeclarationDifferentDisplayName_newVersionAndUpdateMetadata() {
            MetricDeclaration existing = MetricDeclaration.builder()
                    .id(DECLARATION_ID)
                    .providerId(PROVIDER_ID)
                    .name("Accuracy")
                    .displayName("Old Display")
                    .description("Correctness")
                    .build();
            MetricDeclarationVersion latestVersion = MetricDeclarationVersion.builder()
                    .configSchema("{}")
                    .inputSchema("{}")
                    .outputSchema("{}")
                    .displayName("Old Display")
                    .description("Correctness")
                    .build();
            when(metricProviderClient.getMetrics(PROVIDER_ID))
                    .thenReturn(MetricsResponseDto.builder()
                            .metrics(List.of(MetricsDescriptionDto.builder()
                                    .name("Accuracy")
                                    .displayName("New Display")
                                    .description("Correctness")
                                    .configSchema("{}")
                                    .inputSchema("{}")
                                    .outputSchema("{}")
                                    .build()))
                            .build());
            when(metricDeclarationRepository.findByProviderIdAndName(PROVIDER_ID, "Accuracy"))
                    .thenReturn(Optional.of(existing));
            when(metricDeclarationVersionRepository.findLatestByMetricDeclarationId(DECLARATION_ID))
                    .thenReturn(Optional.of(latestVersion));

            syncService.syncOne(PROVIDER_ID);

            verify(metricDeclarationRepository)
                    .updateMetadata(eq(DECLARATION_ID), eq("Correctness"), eq("New Display"));
            ArgumentCaptor<MetricDeclarationVersion> versionCaptor =
                    ArgumentCaptor.forClass(MetricDeclarationVersion.class);
            verify(metricDeclarationVersionRepository).save(versionCaptor.capture());
            assertThat(versionCaptor.getValue().getDisplayName()).isEqualTo("New Display");
        }

        @Test
        @DisplayName("creates new version and updates metadata when description differs")
        void existingDeclarationDifferentDescription_newVersionAndUpdateMetadata() {
            MetricDeclaration existing = MetricDeclaration.builder()
                    .id(DECLARATION_ID)
                    .providerId(PROVIDER_ID)
                    .name("Accuracy")
                    .displayName("Exact Match")
                    .description("Old description")
                    .build();
            MetricDeclarationVersion latestVersion = MetricDeclarationVersion.builder()
                    .configSchema("{}")
                    .inputSchema("{}")
                    .outputSchema("{}")
                    .displayName("Exact Match")
                    .description("Old description")
                    .build();
            when(metricProviderClient.getMetrics(PROVIDER_ID))
                    .thenReturn(MetricsResponseDto.builder()
                            .metrics(List.of(MetricsDescriptionDto.builder()
                                    .name("Accuracy")
                                    .displayName("Exact Match")
                                    .description("New description")
                                    .configSchema("{}")
                                    .inputSchema("{}")
                                    .outputSchema("{}")
                                    .build()))
                            .build());
            when(metricDeclarationRepository.findByProviderIdAndName(PROVIDER_ID, "Accuracy"))
                    .thenReturn(Optional.of(existing));
            when(metricDeclarationVersionRepository.findLatestByMetricDeclarationId(DECLARATION_ID))
                    .thenReturn(Optional.of(latestVersion));

            syncService.syncOne(PROVIDER_ID);

            verify(metricDeclarationRepository)
                    .updateMetadata(eq(DECLARATION_ID), eq("New description"), eq("Exact Match"));
            ArgumentCaptor<MetricDeclarationVersion> versionCaptor =
                    ArgumentCaptor.forClass(MetricDeclarationVersion.class);
            verify(metricDeclarationVersionRepository).save(versionCaptor.capture());
            assertThat(versionCaptor.getValue().getDescription()).isEqualTo("New description");
        }

        @Test
        @DisplayName("creates new version when config schema differs structurally")
        void existingDeclarationDifferentConfigSchema_newVersion() {
            MetricDeclaration existing = MetricDeclaration.builder()
                    .id(DECLARATION_ID)
                    .providerId(PROVIDER_ID)
                    .name("Accuracy")
                    .displayName(null)
                    .description("Desc")
                    .build();
            MetricDeclarationVersion latestVersion = MetricDeclarationVersion.builder()
                    .configSchema("{\"type\":\"object\"}")
                    .inputSchema("{}")
                    .outputSchema("{}")
                    .displayName(null)
                    .description("Desc")
                    .build();
            when(metricProviderClient.getMetrics(PROVIDER_ID))
                    .thenReturn(MetricsResponseDto.builder()
                            .metrics(List.of(MetricsDescriptionDto.builder()
                                    .name("Accuracy")
                                    .description("Desc")
                                    .configSchema("{\"type\":\"object\",\"properties\":{}}")
                                    .inputSchema("{}")
                                    .outputSchema("{}")
                                    .build()))
                            .build());
            when(metricDeclarationRepository.findByProviderIdAndName(PROVIDER_ID, "Accuracy"))
                    .thenReturn(Optional.of(existing));
            when(metricDeclarationVersionRepository.findLatestByMetricDeclarationId(DECLARATION_ID))
                    .thenReturn(Optional.of(latestVersion));

            syncService.syncOne(PROVIDER_ID);

            verify(metricDeclarationVersionRepository).save(any(MetricDeclarationVersion.class));
        }
    }
}
