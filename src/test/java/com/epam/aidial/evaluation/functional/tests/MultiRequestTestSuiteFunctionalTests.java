package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.service.domain.ChainNormalizer;
import com.epam.aidial.evaluation.service.domain.RequestSpec;
import com.epam.aidial.evaluation.service.domain.dto.ChainRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.HttpChainRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.McpToolChainRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end coverage of the multi-request chain through the REST API: persistence round-trip of
 * {@code additionalRequests}/{@code requestLabel}, the hard save-time chain rules (400), and per-element soft
 * validation attributing warnings to their chain request.
 */
@DisplayName("Multi-Request Test Suite Functional Tests")
public abstract class MultiRequestTestSuiteFunctionalTests extends AbstractMultiRequestFunctionalTest {

    @Autowired
    private TestSuiteRepository testSuiteRepository;

    @Autowired
    private ChainNormalizer chainNormalizer;

    // -----------------------------------------------------------------------
    // Persistence round-trip
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST persists a two-element chain and returns it in stored order")
    void createPersistsChain() {
        Dataset dataset = dataset();
        TestSuiteRequestDto request = suiteRequest(dataset.getId());
        request.setRequestLabel("setup");
        request.setResponseColumns(List.of(column("session_id")));
        request.setAdditionalRequests(List.of(
                element("configure", "/configure", List.of(column("config_id")), null),
                element("invoke", "/chat/completions", List.of(column("answer")), "session_id")));

        TestSuiteResponseDto created = post(request);

        assertThat(created.getRequestLabel()).isEqualTo("setup");
        assertThat(created.getAdditionalRequests())
                .extracting(ChainRequestDto::getLabel)
                .containsExactly("configure", "invoke");
        assertThat(created.getAdditionalRequests().get(1).getInputBindings())
                .singleElement()
                .satisfies(binding -> assertThat(binding.getResponseField()).isEqualTo("session_id"));
    }

    @Test
    @DisplayName("GET reads the persisted chain back with every per-element field intact")
    void getReturnsChain() {
        Dataset dataset = dataset();
        TestSuiteRequestDto request = suiteRequest(dataset.getId());
        request.setResponseColumns(List.of(column("session_id")));
        request.setAdditionalRequests(
                List.of(element("invoke", "/chat/completions", List.of(column("answer")), "session_id")));
        TestSuiteResponseDto created = post(request);

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.getForEntity(apiUrl("/test-suites/" + created.getId()), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChainRequestDto element = response.getBody().getAdditionalRequests().getFirst();
        assertThat(element).isInstanceOf(HttpChainRequestDto.class);
        assertThat(element.getEndpointRef().getRelativeUrlPattern()).isEqualTo("/chat/completions");
        assertThat(element.getRequestTemplate().getUrlTemplate()).isEqualTo("/chat/completions");
        assertThat(element.getResponseColumns())
                .extracting(ResponseColumnDefinitionDto::getName)
                .containsExactly("answer");
    }

    @Test
    @DisplayName("PUT replaces the stored chain with the new array")
    void updateReplacesChain() {
        Dataset dataset = dataset();
        TestSuiteRequestDto request = suiteRequest(dataset.getId());
        request.setAdditionalRequests(List.of(element("a", "/a", List.of(), null)));
        TestSuiteResponseDto created = post(request);

        TestSuiteRequestDto update = suiteRequest(dataset.getId());
        update.setName(created.getName());
        update.setAdditionalRequests(List.of(element("b", "/b", List.of(), null), element("c", "/c", List.of(), null)));

        TestSuiteResponseDto updated = put(created, update);

        assertThat(updated.getAdditionalRequests())
                .extracting(ChainRequestDto::getLabel)
                .containsExactly("b", "c");
    }

    @Test
    @DisplayName("PUT with an empty chain reverts the suite to single-request")
    void updateWithEmptyChainRevertsToSingleRequest() {
        Dataset dataset = dataset();
        TestSuiteRequestDto request = suiteRequest(dataset.getId());
        request.setAdditionalRequests(List.of(element("a", "/a", List.of(), null)));
        TestSuiteResponseDto created = post(request);

        TestSuiteRequestDto update = suiteRequest(dataset.getId());
        update.setName(created.getName());
        update.setAdditionalRequests(List.of());
        TestSuiteResponseDto updated = put(created, update);

        assertThat(updated.getAdditionalRequests()).isNullOrEmpty();
        TestSuite stored = testSuiteRepository.findById(created.getId()).orElseThrow();
        assertThat(chainNormalizer.normalize(stored)).hasSize(1);
    }

    @Test
    @DisplayName("a suite created without a chain is single-request and its request 0 resolves to request-1")
    void suiteWithoutChainIsSingleRequest() {
        TestSuiteResponseDto created = post(suiteRequest(dataset().getId()));

        assertThat(created.getAdditionalRequests()).isNullOrEmpty();
        assertThat(created.getRequestLabel()).isNull();

        TestSuite stored = testSuiteRepository.findById(created.getId()).orElseThrow();
        List<RequestSpec> chain = chainNormalizer.normalize(stored);
        assertThat(chain).hasSize(1);
        assertThat(chain.getFirst().label()).isEqualTo("request-1");
    }

    @Test
    @DisplayName("DELETE removes a chain-carrying suite")
    void deleteRemovesChainSuite() {
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setAdditionalRequests(List.of(element("a", "/a", List.of(), null)));
        TestSuiteResponseDto created = post(request);

        ResponseEntity<Void> response =
                restTemplate.exchange(apiUrl("/test-suites/" + created.getId()), HttpMethod.DELETE, null, Void.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(testSuiteRepository.findById(created.getId())).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Hard chain rules — HTTP 400, suite not persisted
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a response column name reused across two chain requests is rejected with 400")
    void duplicateResponseColumnAcrossRequestsRejected() {
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setResponseColumns(List.of(column("answer")));
        request.setAdditionalRequests(List.of(element("invoke", "/b", List.of(column("answer")), null)));

        ResponseEntity<String> response = postRaw(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("answer");
    }

    @Test
    @DisplayName("a duplicate resolved request label is rejected with 400")
    void duplicateLabelRejected() {
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setAdditionalRequests(
                List.of(element("invoke", "/b", List.of(), null), element("invoke", "/c", List.of(), null)));

        ResponseEntity<String> response = postRaw(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("invoke");
    }

    @Test
    @DisplayName("an explicit label colliding with another request's request-{n} default is rejected with 400")
    void labelCollidingWithDefaultRejected() {
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setAdditionalRequests(
                List.of(element(null, "/b", List.of(), null), element("request-2", "/c", List.of(), null)));

        ResponseEntity<String> response = postRaw(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("request-2");
    }

    @Test
    @DisplayName("a forward responseField reference is rejected with 400")
    void forwardResponseFieldRejected() {
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setInputBindings(List.of(InputBindingDto.builder()
                .templateVariable("session")
                .responseField("answer")
                .build()));
        request.setAdditionalRequests(List.of(element("invoke", "/b", List.of(column("answer")), null)));

        ResponseEntity<String> response = postRaw(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("LATER");
    }

    @Test
    @DisplayName("a responseField naming no declared column is rejected with 400")
    void unknownResponseFieldRejected() {
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setAdditionalRequests(List.of(element("invoke", "/b", List.of(), "nonexistent")));

        ResponseEntity<String> response = postRaw(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("nonexistent");
    }

    @Test
    @DisplayName("a responseField on a single-request suite is rejected with 400")
    void responseFieldOnSingleRequestRejected() {
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setInputBindings(List.of(InputBindingDto.builder()
                .templateVariable("session")
                .responseField("session_id")
                .build()));

        ResponseEntity<String> response = postRaw(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("single-request");
    }

    @Test
    @DisplayName("an MCP_TOOL chain element is rejected with 400 — MCP chaining is not supported")
    void mcpChainElementRejected() {
        McpToolChainRequestDto mcp = new McpToolChainRequestDto();
        mcp.setLabel("tool");
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setAdditionalRequests(List.of(mcp));

        ResponseEntity<String> response = postRaw(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("MCP");
    }

    @Test
    @DisplayName("a chain over the configured cap is rejected with 400 naming the length and the cap")
    void overCapChainRejected() {
        // The configured cap is 10, so request 0 plus 10 elements is 11 requests.
        List<ChainRequestDto> tooMany = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            tooMany.add(element("r" + i, "/r" + i, List.of(), null));
        }
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setAdditionalRequests(tooMany);

        ResponseEntity<String> response = postRaw(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("11").contains("10");
    }

    @Test
    @DisplayName("a chain exactly at the configured cap is accepted")
    void atCapChainAccepted() {
        List<ChainRequestDto> exactly = new java.util.ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            exactly.add(element("r" + i, "/r" + i, List.of(), null));
        }
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setAdditionalRequests(exactly);

        TestSuiteResponseDto created = post(request);

        assertThat(created.getAdditionalRequests()).hasSize(9);
    }

    @Test
    @DisplayName("a valid chain persists as valid with no chain-related warnings")
    void validChainPersistsAsValid() {
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setResponseColumns(List.of(column("session_id")));
        request.setAdditionalRequests(
                List.of(element("invoke", "/chat/completions", List.of(column("answer")), "session_id")));

        TestSuiteResponseDto created = post(request);

        assertThat(created.isValid()).isTrue();
        assertThat(created.getValidationWarnings()).isNullOrEmpty();
    }

    // -----------------------------------------------------------------------
    // Soft per-element validation — warnings carry their requestIndex
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("an unbound variable in a chain element warns with that element's requestIndex")
    void chainElementWarningCarriesRequestIndex() {
        HttpChainRequestDto broken = new HttpChainRequestDto();
        broken.setLabel("invoke");
        broken.setEndpointRef(endpoint("/chat/completions"));
        // ${{missing}} has neither a binding nor a default, which is a soft validation failure.
        broken.setRequestTemplate(RequestTemplateDto.builder()
                .urlTemplate("/chat/completions/${{missing}}")
                .build());

        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setAdditionalRequests(List.of(element("a", "/a", List.of(), null), broken));

        TestSuiteResponseDto created = post(request);

        assertThat(created.isValid()).isFalse();
        assertThat(created.getValidationWarnings())
                .anySatisfy(warning -> assertThat(warning.getRequestIndex()).isEqualTo(2));
    }

    @Test
    @DisplayName("a warning on request 0 omits requestIndex, so non-chain warnings are unchanged")
    void requestZeroWarningOmitsRequestIndex() {
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setRequestTemplate(RequestTemplateDto.builder()
                .urlTemplate("/v1/chat/${{missing}}")
                .build());

        TestSuiteResponseDto created = post(request);

        assertThat(created.isValid()).isFalse();
        assertThat(created.getValidationWarnings())
                .allSatisfy(warning -> assertThat(warning.getRequestIndex()).isNull());
    }

    @Test
    @DisplayName("a chain element's body is validated against its OWN endpoint schema, producing no warning")
    void chainElementValidatesAgainstOwnEndpoint() {
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setAdditionalRequests(List.of(element("invoke", "/chat/completions", List.of(), null)));

        TestSuiteResponseDto created = post(request);

        assertThat(created.isValid()).isTrue();
    }

    // -----------------------------------------------------------------------
    // Per-element hard checks — a chain element earns the same 400 as request 0
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("two bindings for one templateVariable inside a chain element are rejected with 400")
    void duplicateTemplateVariableInChainElementRejected() {
        // Resolution keeps the first of two bindings for a variable and silently drops the second, so this
        // has to be caught at save — exactly as it already is on request 0.
        HttpChainRequestDto invoke = new HttpChainRequestDto();
        invoke.setLabel("invoke");
        invoke.setEndpointRef(endpoint("/b"));
        invoke.setRequestTemplate(RequestTemplateDto.builder().urlTemplate("/b").build());
        invoke.setInputBindings(List.of(
                InputBindingDto.builder()
                        .templateVariable("q")
                        .constantValue("first")
                        .build(),
                InputBindingDto.builder()
                        .templateVariable("q")
                        .constantValue("second")
                        .build()));
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setAdditionalRequests(List.of(invoke));

        ResponseEntity<String> response = postRaw(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Duplicate templateVariable").contains("q");
    }

    @Test
    @DisplayName("the same duplicate templateVariable on request 0 keeps its existing 400 and message")
    void duplicateTemplateVariableOnRequestZeroUnchanged() {
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setInputBindings(List.of(
                InputBindingDto.builder()
                        .templateVariable("q")
                        .constantValue("first")
                        .build(),
                InputBindingDto.builder()
                        .templateVariable("q")
                        .constantValue("second")
                        .build()));

        ResponseEntity<String> response = postRaw(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .contains("Duplicate templateVariable 'q' in inputBindings")
                .doesNotContain("additionalRequests");
    }

    @Test
    @DisplayName("a malformed JSONata expression on a chain element's response column is rejected with 400")
    void malformedJsonataInChainElementRejected() {
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setAdditionalRequests(List.of(element(
                "invoke",
                "/b",
                List.of(ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("$.[[[")
                        .build()),
                null)));

        ResponseEntity<String> response = postRaw(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("additionalRequests[0].responseColumns[0]");
    }

    @Test
    @DisplayName("a blank response column name on a chain element is rejected with 400")
    void blankColumnNameInChainElementRejected() {
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setAdditionalRequests(List.of(element(
                "invoke",
                "/b",
                List.of(ResponseColumnDefinitionDto.builder()
                        .name("  ")
                        .expression("$.answer")
                        .build()),
                null)));

        ResponseEntity<String> response = postRaw(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("additionalRequests[0].responseColumns[0]");
    }

    @Test
    @DisplayName("a duplicate column name within ONE chain element is rejected with 400")
    void duplicateColumnWithinOneChainElementRejected() {
        TestSuiteRequestDto request = suiteRequest(dataset().getId());
        request.setAdditionalRequests(
                List.of(element("invoke", "/b", List.of(column("answer"), column("answer")), null)));

        ResponseEntity<String> response = postRaw(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("answer");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Dataset dataset() {
        return metaTestDataHelper.createDataset("MultiRequest-" + UUID.randomUUID());
    }

    private TestSuiteRequestDto suiteRequest(UUID datasetId) {
        return TestSuiteRequestDto.builder()
                .name("Chain-Suite-" + UUID.randomUUID())
                .description("multi-request chain")
                .datasetId(datasetId)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(endpoint("/v1/chat"))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .build();
    }

    private static ResponseColumnDefinitionDto column(String name) {
        return ResponseColumnDefinitionDto.builder()
                .name(name)
                .expression("$." + name)
                .build();
    }

    /** A chain element; {@code responseField} non-null adds a binding consuming that earlier column. */
    private static ChainRequestDto element(
            String label, String path, List<ResponseColumnDefinitionDto> columns, String responseField) {
        HttpChainRequestDto element = new HttpChainRequestDto();
        element.setLabel(label);
        element.setEndpointRef(endpoint(path));
        element.setRequestTemplate(
                RequestTemplateDto.builder().urlTemplate(path).build());
        element.setResponseColumns(columns);
        if (responseField != null) {
            element.setInputBindings(List.of(InputBindingDto.builder()
                    .templateVariable("prev")
                    .responseField(responseField)
                    .build()));
        }
        return element;
    }

    private TestSuiteResponseDto post(TestSuiteRequestDto request) {
        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private ResponseEntity<String> postRaw(TestSuiteRequestDto request) {
        return restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), String.class);
    }

    private TestSuiteResponseDto put(TestSuiteResponseDto existing, TestSuiteRequestDto update) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setIfMatch("\"" + existing.getVersion() + "\"");
        ResponseEntity<TestSuiteResponseDto> response = restTemplate.exchange(
                apiUrl("/test-suites/" + existing.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(update, headers),
                TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }
}
