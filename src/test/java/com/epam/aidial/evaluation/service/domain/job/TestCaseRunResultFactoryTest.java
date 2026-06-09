package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.TestCaseRunResult;
import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestCaseRunResultFactory")
class TestCaseRunResultFactoryTest {

    private static final long NOW_MS = 1_700_000_000_000L;

    private ObjectMapper objectMapper;
    private TestCaseRunResultFactory factory;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        factory = new TestCaseRunResultFactory(objectMapper);
    }

    private TestCaseRunInput buildInput() {
        return TestCaseRunInput.builder()
                .runId(UUID.randomUUID())
                .position(3)
                .testCaseId(UUID.randomUUID())
                .testCaseName("case-X")
                .testCaseData("{\"k\":\"v\"}")
                .build();
    }

    @Test
    @DisplayName("errorResult builds expected ERROR envelope and timing fields")
    void errorResult_buildsExpectedEnvelope() throws Exception {
        TestCaseRunInput input = buildInput();

        TestCaseRunResult row = factory.errorResult(input, 2, new RuntimeException("boom"), NOW_MS);

        assertThat(row.getExecutionStatus()).isEqualTo(ExecutionStatus.ERROR);
        assertThat(row.getResponseStatusCode()).isNull();
        assertThat(row.getExecStartedAtMs()).isEqualTo(NOW_MS);
        assertThat(row.getExecCompletedAtMs()).isEqualTo(NOW_MS);
        assertThat(row.getExecDurationMs()).isEqualTo(0L);
        assertThat(row.getRetryCount()).isEqualTo(0);
        assertThat(row.getLogDetails()).isNull();
        assertThat(row.getCreatedAtMs()).isEqualTo(NOW_MS);

        JsonNode envelope = objectMapper.readTree(row.getResponseBody());
        assertThat(envelope.get("error").get("type").asText()).isEqualTo("RuntimeException");
        assertThat(envelope.get("error").get("message").asText()).isEqualTo("boom");
        assertThat(envelope.get("error").get("origin").asText()).isEqualTo("executor");
    }

    @Test
    @DisplayName("errorResult maps null cause message to empty string in envelope")
    void errorResult_handlesNullMessage() throws Exception {
        TestCaseRunInput input = buildInput();
        Throwable cause = new Throwable() {
            @Override
            public String getMessage() {
                return null;
            }
        };

        TestCaseRunResult row = factory.errorResult(input, 0, cause, NOW_MS);

        JsonNode envelope = objectMapper.readTree(row.getResponseBody());
        assertThat(envelope.get("error").get("message").asText()).isEmpty();
    }

    @Test
    @DisplayName("errorResult never throws, even when getMessage() throws")
    void errorResult_neverThrows() {
        TestCaseRunInput input = buildInput();
        Throwable adversarial = new Throwable() {
            @Override
            public String getMessage() {
                throw new IllegalStateException("inner");
            }
        };

        assertThatNoException().isThrownBy(() -> {
            TestCaseRunResult row = factory.errorResult(input, 0, adversarial, NOW_MS);
            assertThat(row).isNotNull();
            assertThat(row.getResponseBody()).isNotBlank();
            // Must be valid JSON regardless of the adversarial input
            objectMapper.readTree(row.getResponseBody());
        });
    }

    @Test
    @DisplayName("errorResult copies input fields onto the synthetic row")
    void errorResult_copiesInputFields() {
        TestCaseRunInput input = buildInput();

        TestCaseRunResult row = factory.errorResult(input, 7, new RuntimeException("x"), NOW_MS);

        assertThat(row.getId()).isNotNull();
        assertThat(row.getTestSuiteRunId()).isEqualTo(input.getRunId());
        assertThat(row.getTestCaseId()).isEqualTo(input.getTestCaseId());
        assertThat(row.getTestCaseName()).isEqualTo(input.getTestCaseName());
        assertThat(row.getTestCaseData()).isEqualTo(input.getTestCaseData());
        assertThat(row.getRunIndex()).isEqualTo(7);
    }
}
