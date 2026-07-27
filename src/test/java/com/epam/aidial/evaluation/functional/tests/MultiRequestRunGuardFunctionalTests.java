package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.service.domain.dto.ChainRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.HttpChainRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Run-creation guards for multi-request suites. Both are checked at run creation rather than suite save:
 * the chain cap because the configured maximum can be lowered after a suite is persisted, and the multi-turn
 * exclusion because dataset content is mutable and stored suite validity is configuration-only.
 */
@DisplayName("Multi-Request Run Guard Functional Tests")
public abstract class MultiRequestRunGuardFunctionalTests extends AbstractMultiRequestFunctionalTest {

    private static final String NAME_PREFIX = "MRGuard-";

    @Test
    @DisplayName("a multi-request suite over a dataset containing a multi-turn case is rejected with 409")
    void multiRequestOverMultiTurnDatasetRejected() {
        UUID dataset = datasetWithPromptField(true);
        createMultiTurnCase(dataset, "mt-case", List.of(Map.of("prompt", "one"), Map.of("prompt", "two")));
        TestSuiteResponseDto suite = createChainSuite(dataset, 2);

        ResponseEntity<String> response = triggerRun(suite.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("multi-request").contains("multi-turn");
    }

    @Test
    @DisplayName("a SINGLE-request suite over a dataset containing multi-turn cases still passes the guards")
    void singleRequestOverMultiTurnDatasetPassesGuards() {
        UUID dataset = datasetWithPromptField(true);
        createMultiTurnCase(dataset, "mt-case", List.of(Map.of("prompt", "one"), Map.of("prompt", "two")));
        TestSuiteResponseDto suite = createChainSuite(dataset, 0);

        ResponseEntity<String> response = triggerRun(suite.getId());

        // The chain guards must not fire; the run is accepted (202) as it was before this capability existed.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("a multi-request suite over a single-turn dataset passes the guards and the run is accepted")
    void multiRequestOverSingleTurnDatasetAccepted() {
        UUID dataset = datasetWithPromptField(false);
        createSingleTurnCase(dataset, "st-case", Map.of("prompt", "one"));
        TestSuiteResponseDto suite = createChainSuite(dataset, 2);

        ResponseEntity<String> response = triggerRun(suite.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("adding a multi-turn case after a successful chain run blocks the NEXT run")
    void addingMultiTurnCaseBlocksSubsequentRuns() {
        // Both scopes in one schema, so the dataset can hold a single-turn case AND later a multi-turn one:
        // the test-case write path rejects a per-turn map carrying a shared-scoped field.
        UUID dataset = datasetWithBothScopes();
        createSingleTurnCase(dataset, "st-case", Map.of("prompt", "one"));
        TestSuiteResponseDto suite = createChainSuite(dataset, 2);

        assertThat(triggerRun(suite.getId()).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        createMultiTurnCase(
                dataset,
                "mt-added",
                Map.of("prompt", "shared"),
                List.of(Map.of("turnText", "a"), Map.of("turnText", "b")));

        assertThat(triggerRun(suite.getId()).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Creates a suite whose normalized chain has {@code additionalCount + 1} requests. */
    private TestSuiteResponseDto createChainSuite(UUID datasetId, int additionalCount) {
        List<ChainRequestDto> extra = new java.util.ArrayList<>();
        for (int i = 1; i <= additionalCount; i++) {
            HttpChainRequestDto element = new HttpChainRequestDto();
            element.setLabel("r" + i);
            element.setEndpointRef(endpoint("/r" + i));
            element.setRequestTemplate(
                    RequestTemplateDto.builder().urlTemplate("/r" + i).build());
            extra.add(element);
        }

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("MRGuard-Suite-" + UUID.randomUUID())
                .datasetId(datasetId)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(endpoint("/v1/chat"))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .additionalRequests(extra.isEmpty() ? null : extra)
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isValid())
                .as("the fixture suite must be config-valid so the chain guards are the ones under test")
                .isTrue();
        return response.getBody();
    }

    /**
     * A dataset whose schema declares a single {@code prompt} field, so the seeded test cases are valid and
     * runnable. Scope matters: a multi-turn case's fields must be {@code perTurn}, a single-turn case's must
     * not be — otherwise the case is invalid and the zero-runnable guard fires before the chain guards.
     */
    private UUID datasetWithPromptField(boolean perTurn) {
        return datasetWithPromptField(NAME_PREFIX, perTurn);
    }

    /**
     * A dataset whose schema carries a shared {@code prompt} and a per-turn {@code turnText}, so it can hold
     * both a single-turn case (shared fields only) and a multi-turn case (shared plus per-turn maps).
     */
    private UUID datasetWithBothScopes() {
        return createDatasetWithSchema(
                NAME_PREFIX,
                List.of(
                        FieldDefinitionDto.builder()
                                .name("prompt")
                                .type(SchemaFieldType.STRING)
                                .required(true)
                                .build(),
                        FieldDefinitionDto.builder()
                                .name("turnText")
                                .type(SchemaFieldType.STRING)
                                .required(false)
                                .perTurn(true)
                                .build()));
    }
}
