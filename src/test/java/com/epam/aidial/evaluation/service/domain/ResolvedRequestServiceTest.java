package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.runner.service.RequestResolver;
import com.epam.aidial.evaluation.runner.util.ValidationWarningsSerializer;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the DB-backed Try-It-Out overload loads the suite/test-case and delegates the actual
 * resolution to {@link RequestResolver}. The resolution logic itself is covered by
 * {@code RequestResolverTest} in the shared module.
 */
@DisplayName("ResolvedRequestService")
@ExtendWith(MockitoExtension.class)
class ResolvedRequestServiceTest {

    private static final UUID SUITE_ID = UUID.randomUUID();
    private static final UUID TEST_CASE_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();

    @Mock
    private TestSuiteRepository testSuiteRepository;

    @Mock
    private TestCaseRepository testCaseRepository;

    @Mock
    private JsonbMapper jsonbMapper;

    @Mock
    private ValidationWarningsSerializer warningsSerializer;

    @Mock
    private RequestResolver requestResolver;

    @InjectMocks
    private ResolvedRequestService service;

    @Test
    @DisplayName("loads suite and test case, then delegates resolution to RequestResolver")
    void resolveRequest_delegatesToRequestResolver() {
        TestSuite suite = TestSuite.builder()
                .id(SUITE_ID)
                .datasetId(DATASET_ID)
                .requestTemplate("{}")
                .inputBindings("[]")
                .build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
        TestCase testCase = TestCase.builder()
                .id(TEST_CASE_ID)
                .data("{\"field\":\"value\"}")
                .build();
        when(testCaseRepository.findByIdAndDatasetId(TEST_CASE_ID, DATASET_ID)).thenReturn(Optional.of(testCase));

        RequestTemplateDto template = RequestTemplateDto.builder().build();
        List<InputBindingDto> bindings = List.of();
        Map<String, Object> data = Map.of("field", "value");
        when(jsonbMapper.mapRequestTemplate("{}")).thenReturn(template);
        when(jsonbMapper.mapInputBindings("[]")).thenReturn(bindings);
        when(warningsSerializer.deserializeMap("{\"field\":\"value\"}")).thenReturn(data);

        ResolvedRequestDto expected =
                ResolvedRequestDto.builder().url("/resolved").build();
        when(requestResolver.resolve(template, bindings, data)).thenReturn(expected);

        ResolvedRequestDto result = service.resolveRequest(SUITE_ID, TEST_CASE_ID);

        assertThat(result).isSameAs(expected);
        verify(requestResolver).resolve(template, bindings, data);
    }

    @Test
    @DisplayName("throws EntityNotFoundException when suite does not exist")
    void resolveRequest_throwsWhenSuiteMissing() {
        UUID missingSuiteId = UUID.randomUUID();
        when(testSuiteRepository.findById(missingSuiteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveRequest(missingSuiteId, TEST_CASE_ID))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("TestSuite not found");
    }

    @Test
    @DisplayName("throws EntityNotFoundException when test case does not exist in the suite's dataset")
    void resolveRequest_throwsWhenTestCaseMissing() {
        TestSuite suite = TestSuite.builder().id(SUITE_ID).datasetId(DATASET_ID).build();
        when(testSuiteRepository.findById(SUITE_ID)).thenReturn(Optional.of(suite));
        when(testCaseRepository.findByIdAndDatasetId(eq(TEST_CASE_ID), eq(DATASET_ID)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveRequest(SUITE_ID, TEST_CASE_ID))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("TestCase not found");

        verify(requestResolver, never()).resolve(any(), any(), any());
    }
}
