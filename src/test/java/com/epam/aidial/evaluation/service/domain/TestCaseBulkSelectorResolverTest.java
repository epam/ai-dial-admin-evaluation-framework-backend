package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.configuration.properties.testcase.TestCaseProperties;
import com.epam.aidial.evaluation.data.db.exception.InvalidFilterException;
import com.epam.aidial.evaluation.data.db.model.filter.FilterCondition;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.service.domain.dto.testcase.bulk.TestCaseBulkSelectorDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.FilterValidationException;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import com.epam.aidial.evaluation.service.domain.filter.FilterParser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TestCaseBulkSelectorResolverTest {

    private TestCaseRepository testCaseRepository;
    private FilterParser filterParser;
    private TestCaseBulkSelectorResolver resolver;

    @BeforeEach
    void setUp() {
        testCaseRepository = mock(TestCaseRepository.class);
        filterParser = mock(FilterParser.class);
        TestCaseProperties props = new TestCaseProperties();
        TestCaseProperties.Bulk bulk = new TestCaseProperties.Bulk();
        bulk.setMaxOperations(512);
        bulk.setMaxIdsPerSelector(10);
        bulk.setMaxItemOperations(500);
        props.setBulk(bulk);
        resolver = new TestCaseBulkSelectorResolver(testCaseRepository, filterParser, props);
    }

    @Test
    @DisplayName("Should pass-through ids when all belong to suite")
    void shouldPassThroughIds() {
        UUID suiteId = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(testCaseRepository.findExistingIdsInDataset(eq(suiteId), any())).thenReturn(List.of(id1, id2));

        List<UUID> result = resolver.resolve(
                suiteId,
                TestCaseBulkSelectorDto.builder().ids(List.of(id1, id2)).build());

        assertThat(result).containsExactly(id1, id2);
    }

    @Test
    @DisplayName("Should throw EntityNotFoundException when an id is missing from suite")
    void shouldThrowWhenIdNotInSuite() {
        UUID suiteId = UUID.randomUUID();
        UUID present = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(testCaseRepository.findExistingIdsInDataset(eq(suiteId), any())).thenReturn(List.of(present));

        assertThatThrownBy(() -> resolver.resolve(
                        suiteId,
                        TestCaseBulkSelectorDto.builder()
                                .ids(List.of(present, missing))
                                .build()))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(missing.toString());
    }

    @Test
    @DisplayName("Should resolve filter selector to id list within cap")
    void shouldResolveFilterToIdsWithinCap() {
        UUID suiteId = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(filterParser.parse(any()))
                .thenReturn(List.of(FilterCondition.builder().build()));
        when(testCaseRepository.findIdsByDatasetIdAndFilter(eq(suiteId), any(), anyInt()))
                .thenReturn(List.of(id1, id2));

        List<UUID> result = resolver.resolve(
                suiteId,
                TestCaseBulkSelectorDto.builder()
                        .filter(List.of("enabled:eq:true"))
                        .build());

        assertThat(result).containsExactly(id1, id2);
    }

    @Test
    @DisplayName("Should throw ValidationException when filter selector exceeds cap")
    void shouldThrowWhenFilterExceedsCap() {
        UUID suiteId = UUID.randomUUID();
        when(filterParser.parse(any())).thenReturn(List.of());
        // resolver passes cap+1 to repo; if repo returns more than cap, reject.
        List<UUID> overflow = List.of(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()); // 11 > cap 10
        when(testCaseRepository.findIdsByDatasetIdAndFilter(eq(suiteId), any(), anyInt()))
                .thenReturn(overflow);

        assertThatThrownBy(() -> resolver.resolve(
                        suiteId,
                        TestCaseBulkSelectorDto.builder().filter(List.of()).build()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("matched more than 10");
    }

    @Test
    @DisplayName("Should rewrap InvalidFilterException as FilterValidationException")
    void shouldRewrapInvalidFilterException() {
        UUID suiteId = UUID.randomUUID();
        when(filterParser.parse(any())).thenReturn(List.of());
        when(testCaseRepository.findIdsByDatasetIdAndFilter(eq(suiteId), any(), anyInt()))
                .thenThrow(new InvalidFilterException("unknown field 'foo'", Map.of("field", "foo")));

        assertThatThrownBy(() -> resolver.resolve(
                        suiteId,
                        TestCaseBulkSelectorDto.builder()
                                .filter(List.of("foo:eq:bar"))
                                .build()))
                .isInstanceOf(FilterValidationException.class)
                .hasMessageContaining("unknown field");
    }

    @Test
    @DisplayName("Should accept empty filter list (matches all rows in suite)")
    void shouldAcceptEmptyFilter() {
        UUID suiteId = UUID.randomUUID();
        when(filterParser.parse(any())).thenReturn(List.of());
        when(testCaseRepository.findIdsByDatasetIdAndFilter(eq(suiteId), any(), anyInt()))
                .thenReturn(List.of());

        List<UUID> result = resolver.resolve(
                suiteId, TestCaseBulkSelectorDto.builder().filter(List.of()).build());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should return empty list for empty ids selector")
    void shouldReturnEmptyForEmptyIds() {
        UUID suiteId = UUID.randomUUID();

        List<UUID> result = resolver.resolve(
                suiteId, TestCaseBulkSelectorDto.builder().ids(List.of()).build());

        assertThat(result).isEmpty();
    }
}
