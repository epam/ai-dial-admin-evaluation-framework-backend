package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.HttpChainRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared fixtures for multi-request (chain) functional tests: chain-element and endpoint construction,
 * dataset/test-case authoring, and run triggering. Mirrors {@link AbstractMultiTurnFunctionalTest}, which
 * plays the same role for the sibling multi-turn feature.
 *
 * <p>Reused by {@link MultiRequestTestSuiteFunctionalTests}, {@link MultiRequestRunGuardFunctionalTests} and
 * {@link MultiRequestExecutionFunctionalTests}, which previously carried byte-identical copies of
 * {@code endpoint(...)} and {@code createSingleTurnCase(...)}.
 */
public abstract class AbstractMultiRequestFunctionalTest extends BaseFunctionalTest {

    @Autowired
    protected MetaTestDataHelper metaTestDataHelper;

    @Autowired
    protected ObjectMapper objectMapper;

    /** A POST endpoint contract for {@code path} — the shape every chain fixture in these tests uses. */
    protected static EndpointContractDto endpoint(String path) {
        return EndpointContractDto.builder()
                .method(HttpMethod.POST)
                .relativeUrlPattern(path)
                .build();
    }

    /** A response column whose JSONata expression is supplied verbatim. */
    protected static ResponseColumnDefinitionDto responseColumn(String name, String expression) {
        return ResponseColumnDefinitionDto.builder()
                .name(name)
                .expression(expression)
                .type(SchemaFieldType.STRING)
                .build();
    }

    /** An HTTP chain element with its own endpoint, template, bindings and response columns. */
    protected static HttpChainRequestDto chainElement(
            String label,
            String path,
            List<InputBindingDto> inputBindings,
            List<ResponseColumnDefinitionDto> responseColumns) {
        HttpChainRequestDto element = new HttpChainRequestDto();
        element.setLabel(label);
        element.setEndpointRef(endpoint(path));
        element.setRequestTemplate(
                RequestTemplateDto.builder().urlTemplate(path).build());
        element.setInputBindings(inputBindings);
        element.setResponseColumns(responseColumns);
        return element;
    }

    protected TestCaseResponseDto createSingleTurnCase(UUID datasetId, String name, Map<String, Object> data) {
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .data(data)
                        .build()),
                TestCaseResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    protected TestCaseResponseDto createMultiTurnCase(UUID datasetId, String name, List<Map<String, Object>> turns) {
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .multiTurnData(turns)
                        .build()),
                TestCaseResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    /** A multi-turn case carrying both shared (test-case-level) {@code data} and per-turn maps. */
    protected TestCaseResponseDto createMultiTurnCase(
            UUID datasetId, String name, Map<String, Object> sharedData, List<Map<String, Object>> turns) {
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .data(sharedData)
                        .multiTurnData(turns)
                        .build()),
                TestCaseResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    /** A dataset whose test-case schema is the given field list, serialized with the application mapper. */
    protected UUID createDatasetWithSchema(String namePrefix, List<FieldDefinitionDto> schema) {
        try {
            return metaTestDataHelper
                    .createDataset(namePrefix + UUID.randomUUID(), objectMapper.writeValueAsString(schema))
                    .getId();
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    /**
     * A dataset declaring a single required {@code prompt} field. Scope matters: a multi-turn case's fields
     * must be {@code perTurn} and a single-turn case's must not, or the case is invalid and the
     * zero-runnable-cases guard fires before whatever the test actually targets.
     */
    protected UUID datasetWithPromptField(String namePrefix, boolean perTurn) {
        return createDatasetWithSchema(
                namePrefix,
                List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .perTurn(perTurn)
                        .build()));
    }

    protected ResponseEntity<String> triggerRun(UUID suiteId) {
        return restTemplate.postForEntity(
                apiUrl("/test-suites/" + suiteId + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                String.class);
    }
}
