package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Functional tests for coexisting shared (test-case-level) and per-turn data on a multi-turn case: a shared
 * field is visible to the template on every turn (merged effective view), the suite {@code testCaseFilter}
 * binds a shared field at row level, and misplacing a shared field inside a turn map is rejected with 400.
 */
@DisplayName("Multi-turn Shared-Data Functional Tests")
public abstract class MultiTurnSharedDataFunctionalTests extends AbstractMultiTurnFunctionalTest {

    /** {@code data::system eq <value>} as a raw StructuredQuery filter map. */
    private static Map<String, Object> systemEquals(String value) {
        return Map.of(
                "op",
                "eq",
                "args",
                List.of(
                        Map.of("type", "field", "name", "data::system"),
                        Map.of("type", "value", "value_type", "string", "value", value)));
    }

    private void stubDeployment() {
        AtomicInteger call = new AtomicInteger();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> chatReply("reply-" + call.getAndIncrement()));
    }

    /**
     * Chat suite whose dataset declares a per-turn {@code prompt} and a shared {@code system}; the template
     * injects the shared {@code system} as a system message on every turn plus the per-turn {@code prompt}.
     */
    private TestSuiteResponseDto createSharedFieldSuite(String name, Map<String, Object> testCaseFilter) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name + " " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(
                        FieldDefinitionDto.builder()
                                .name("prompt")
                                .type(SchemaFieldType.STRING)
                                .required(true)
                                .perTurn(true)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("system")
                                .type(SchemaFieldType.STRING)
                                .required(false)
                                .perTurn(false)
                                .build())))
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/chat")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of(
                                        "messages",
                                        List.of(
                                                Map.of("role", "system", "content", "${{system}}"),
                                                Map.of("role", "user", "content", "${{prompt}}"))))
                                .build())
                        .build())
                .inputBindings(List.of(
                        InputBindingDto.builder()
                                .templateVariable("prompt")
                                .dataField("prompt")
                                .build(),
                        InputBindingDto.builder()
                                .templateVariable("system")
                                .dataField("system")
                                .build()))
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("choices[0].message.content")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .testCaseFilter(testCaseFilter)
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    @Test
    @DisplayName("A shared field is injected into every turn's request via the merged effective view")
    void sharedFieldVisibleOnEveryTurn() {
        TestSuiteResponseDto suite = createSharedFieldSuite("MT shared visible", null);
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createMultiTurnCase(
                datasetId, "conv", Map.of("system", "SYSVAL"), List.of(Map.of("prompt", "q0"), Map.of("prompt", "q1")));
        stubDeployment();

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(r -> {
            assertThat(String.valueOf(r.get("execution_status"))).isEqualTo("SUCCESS");
            // The shared `system` value appears in every turn's request body (constant across turns).
            assertThat(String.valueOf(r.get("request_body"))).contains("SYSVAL");
        });
    }

    @Test
    @DisplayName("A filter on a shared field selects at case level (row-level, constant across turns)")
    void sharedFieldFilterSelectsAtCaseLevel() {
        TestSuiteResponseDto suite = createSharedFieldSuite("MT shared filter", systemEquals("keep"));
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createMultiTurnCase(
                datasetId,
                "keep-me",
                Map.of("system", "keep"),
                List.of(Map.of("prompt", "q0"), Map.of("prompt", "q1")));
        createMultiTurnCase(datasetId, "drop-me", Map.of("system", "drop"), List.of(Map.of("prompt", "q0")));
        stubDeployment();

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        Map<String, Long> rows = analyticsTestDataHelper.findResultsByRunId(run.getId()).stream()
                .collect(Collectors.groupingBy(r -> String.valueOf(r.get("test_case_name")), Collectors.counting()));
        assertThat(rows).containsOnlyKeys("keep-me");
        assertThat(rows.get("keep-me")).isEqualTo(2L);
    }

    @Test
    @DisplayName("A case carrying turns in an all-shared dataset schema is stored invalid with a case-level "
            + "$.multiTurnData warning")
    void turnsWithoutPerTurnColumnsStoredInvalid() {
        UUID datasetId = newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                .name("system")
                .type(SchemaFieldType.STRING)
                .required(false)
                .build()));

        // The turn maps must be empty: a declared shared field inside a turn is a 400 (see
        // sharedFieldInTurnRejected) and an undeclared key would add its own unknown-field warning, so
        // empty turns are the only shape that isolates the no-per-turn-columns warning.
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases?includeWarnings=true"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName("no-perturn-columns")
                        .data(Map.of("system", "SYSVAL"))
                        .multiTurnData(List.of(Map.of(), Map.of()))
                        .build()),
                TestCaseResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestCaseResponseDto created = response.getBody();
        assertThat(created).isNotNull();
        assertThat(created.isValid()).isFalse();
        assertThat(created.getValidationWarnings()).hasSize(1);
        ValidationWarningDto warning = created.getValidationWarnings().get(0);
        assertThat(warning.getCode()).isEqualTo(ValidationWarningCode.ADDITIONAL);
        assertThat(warning.getPath()).isEqualTo("$.multiTurnData");
        assertThat(warning.getFieldName()).isNull();
        assertThat(warning.getTurnIndex()).isNull();
        assertThat(warning.getMessage()).contains("2 turns", "no per-turn columns");

        // The other half of the clearing rule (the schema-side half is covered in MultiTurnCsvFunctionalTests):
        // dropping the turns reverts the case to single-turn, so the warning is no longer computed.
        // Raw JSON, not a Map: the shared ObjectMapper's NON_NULL inclusion would drop the explicit null and
        // send `{}`, which merge-PATCH reads as "change nothing".
        ResponseEntity<TestCaseResponseDto> patched = restTemplate.exchange(
                apiUrl("/datasets/" + datasetId + "/test-cases/" + created.getId() + "?includeWarnings=true"),
                HttpMethod.PATCH,
                jsonEntity("{\"multiTurnData\":null}"),
                TestCaseResponseDto.class);
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(patched.getBody()).isNotNull();
        assertThat(patched.getBody().getMultiTurnData()).isNull();
        assertThat(patched.getBody().isValid()).isTrue();
        assertThat(patched.getBody().getValidationWarnings()).isNullOrEmpty();
    }

    @Test
    @DisplayName("A shared field placed inside a turn map is rejected with 400")
    void sharedFieldInTurnRejected() {
        TestSuiteResponseDto suite = createSharedFieldSuite("MT shared placement", null);
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + datasetId + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName("bad-shared")
                        .multiTurnData(List.of(Map.of("prompt", "q0", "system", "in-turn")))
                        .build()),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
