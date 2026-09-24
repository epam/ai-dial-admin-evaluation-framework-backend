package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialadas.DialAdasClient;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasAggregateResponseDto;
import com.epam.aidial.evaluation.client.dialadas.dto.AdasBatchRunCostRowDto;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("BatchRunTotalCostLookup")
@ExtendWith(MockitoExtension.class)
class BatchRunTotalCostLookupTest {

    @Mock
    private AdasCostQueryBuilder adasCostQueryBuilder;

    @Mock
    private DialAdasClient dialAdasClient;

    private BatchRunTotalCostLookup newLookup() {
        return new BatchRunTotalCostLookup(adasCostQueryBuilder);
    }

    private final UUID runId1 = UUID.randomUUID();
    private final UUID runId2 = UUID.randomUUID();

    private final StructuredQuery pageTotalCostQuery = new StructuredQuery(
            "page-total-cost-query", null, QueryMode.AGGREGATE, false, null, null, null, null, null);

    @Test
    @DisplayName("issues exactly one aggregate call to build and execute the page total-cost query")
    void issuesExactlyOneAggregateCall() {
        BatchRunTotalCostLookup batchRunTotalCostLookup = newLookup();
        when(adasCostQueryBuilder.buildPageTotalCostQuery(List.of(runId1, runId2)))
                .thenReturn(pageTotalCostQuery);
        when(dialAdasClient.executeAggregate(pageTotalCostQuery, AdasBatchRunCostRowDto.class))
                .thenReturn(AdasAggregateResponseDto.<AdasBatchRunCostRowDto>builder()
                        .rows(List.of())
                        .build());

        batchRunTotalCostLookup.fetchTotalCosts(List.of(runId1, runId2), dialAdasClient);

        verify(adasCostQueryBuilder, times(1)).buildPageTotalCostQuery(List.of(runId1, runId2));
        verify(dialAdasClient, times(1)).executeAggregate(pageTotalCostQuery, AdasBatchRunCostRowDto.class);
    }

    @Test
    @DisplayName("parses valid grouped rows into a run-id-keyed map")
    void parsesValidGroupedRows() {
        BatchRunTotalCostLookup batchRunTotalCostLookup = newLookup();
        when(adasCostQueryBuilder.buildPageTotalCostQuery(List.of(runId1, runId2)))
                .thenReturn(pageTotalCostQuery);
        when(dialAdasClient.executeAggregate(pageTotalCostQuery, AdasBatchRunCostRowDto.class))
                .thenReturn(AdasAggregateResponseDto.<AdasBatchRunCostRowDto>builder()
                        .rows(List.of(
                                AdasBatchRunCostRowDto.builder()
                                        .runId(runId1.toString())
                                        .totalCost(0.05)
                                        .build(),
                                AdasBatchRunCostRowDto.builder()
                                        .runId(runId2.toString())
                                        .totalCost(1.25)
                                        .build()))
                        .build());

        Map<UUID, Double> result = batchRunTotalCostLookup.fetchTotalCosts(List.of(runId1, runId2), dialAdasClient);

        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(runId1, 0.05, runId2, 1.25));
    }

    @Test
    @DisplayName("returns an empty map for a null response")
    void returnsEmptyMapForNullResponse() {
        BatchRunTotalCostLookup batchRunTotalCostLookup = newLookup();
        when(adasCostQueryBuilder.buildPageTotalCostQuery(List.of(runId1))).thenReturn(pageTotalCostQuery);
        when(dialAdasClient.executeAggregate(pageTotalCostQuery, AdasBatchRunCostRowDto.class))
                .thenReturn(null);

        Map<UUID, Double> result = batchRunTotalCostLookup.fetchTotalCosts(List.of(runId1), dialAdasClient);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns an empty map for null rows")
    void returnsEmptyMapForNullRows() {
        BatchRunTotalCostLookup batchRunTotalCostLookup = newLookup();
        when(adasCostQueryBuilder.buildPageTotalCostQuery(List.of(runId1))).thenReturn(pageTotalCostQuery);
        when(dialAdasClient.executeAggregate(pageTotalCostQuery, AdasBatchRunCostRowDto.class))
                .thenReturn(AdasAggregateResponseDto.<AdasBatchRunCostRowDto>builder()
                        .rows(null)
                        .build());

        Map<UUID, Double> result = batchRunTotalCostLookup.fetchTotalCosts(List.of(runId1), dialAdasClient);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("ignores an unparseable run id, including the \"other\" bucket sentinel")
    void ignoresUnparseableRunIds() {
        BatchRunTotalCostLookup batchRunTotalCostLookup = newLookup();
        when(adasCostQueryBuilder.buildPageTotalCostQuery(List.of(runId1))).thenReturn(pageTotalCostQuery);
        when(dialAdasClient.executeAggregate(pageTotalCostQuery, AdasBatchRunCostRowDto.class))
                .thenReturn(AdasAggregateResponseDto.<AdasBatchRunCostRowDto>builder()
                        .rows(List.of(
                                AdasBatchRunCostRowDto.builder()
                                        .runId("other")
                                        .totalCost(1.5)
                                        .build(),
                                AdasBatchRunCostRowDto.builder()
                                        .runId("not-a-uuid")
                                        .totalCost(2.5)
                                        .build(),
                                AdasBatchRunCostRowDto.builder()
                                        .runId(null)
                                        .totalCost(3.5)
                                        .build()))
                        .build());

        Map<UUID, Double> result = batchRunTotalCostLookup.fetchTotalCosts(List.of(runId1), dialAdasClient);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("a requested run id absent from the grouped rows is simply missing from the map")
    void missingGroupIsAbsentFromMap() {
        BatchRunTotalCostLookup batchRunTotalCostLookup = newLookup();
        when(adasCostQueryBuilder.buildPageTotalCostQuery(List.of(runId1, runId2)))
                .thenReturn(pageTotalCostQuery);
        when(dialAdasClient.executeAggregate(pageTotalCostQuery, AdasBatchRunCostRowDto.class))
                .thenReturn(AdasAggregateResponseDto.<AdasBatchRunCostRowDto>builder()
                        .rows(List.of(AdasBatchRunCostRowDto.builder()
                                .runId(runId1.toString())
                                .totalCost(0.05)
                                .build()))
                        .build());

        Map<UUID, Double> result = batchRunTotalCostLookup.fetchTotalCosts(List.of(runId1, runId2), dialAdasClient);

        assertThat(result).containsOnlyKeys(runId1);
        assertThat(result).doesNotContainKey(runId2);
    }

    @Test
    @DisplayName("a null total cost does not become a row key")
    void nullTotalCostDoesNotBecomeRowKey() {
        BatchRunTotalCostLookup batchRunTotalCostLookup = newLookup();
        when(adasCostQueryBuilder.buildPageTotalCostQuery(List.of(runId1))).thenReturn(pageTotalCostQuery);
        when(dialAdasClient.executeAggregate(pageTotalCostQuery, AdasBatchRunCostRowDto.class))
                .thenReturn(AdasAggregateResponseDto.<AdasBatchRunCostRowDto>builder()
                        .rows(List.of(AdasBatchRunCostRowDto.builder()
                                .runId(runId1.toString())
                                .totalCost(null)
                                .build()))
                        .build());

        Map<UUID, Double> result = batchRunTotalCostLookup.fetchTotalCosts(List.of(runId1), dialAdasClient);

        assertThat(result).doesNotContainKey(runId1);
    }
}
