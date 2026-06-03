package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.TestCaseRunInput;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRunInputRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("PostgresTestCaseRunInputRepository tests")
public abstract class PostgresTestCaseRunInputRepositoryFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private TestCaseRunInputRepository runInputRepository;

    @Test
    @DisplayName("insertBatch persists all rows; findByRunId retrieves them ordered by position")
    void insertBatchAndFindByRunId() {
        UUID suiteId = metaTestDataHelper.createTestSuite("Input Repo Suite").getId();
        UUID runId = metaTestDataHelper.createTestSuiteRun(suiteId).getId();
        UUID tcId1 = UUID.randomUUID();
        UUID tcId2 = UUID.randomUUID();

        List<TestCaseRunInput> inputs = List.of(
                TestCaseRunInput.builder()
                        .runId(runId)
                        .position(0)
                        .testCaseId(tcId1)
                        .testCaseName("TC1")
                        .testCaseData("{\"query\":\"hello\"}")
                        .build(),
                TestCaseRunInput.builder()
                        .runId(runId)
                        .position(1)
                        .testCaseId(tcId2)
                        .testCaseName("TC2")
                        .testCaseData("{\"query\":\"world\"}")
                        .build());

        runInputRepository.insertBatch(inputs);

        List<TestCaseRunInput> found = runInputRepository.findByRunId(runId, 0, 100);

        assertThat(found).hasSize(2);
        assertThat(found.get(0).getPosition()).isEqualTo(0);
        assertThat(found.get(0).getTestCaseName()).isEqualTo("TC1");
        assertThat(found.get(1).getPosition()).isEqualTo(1);
        assertThat(found.get(1).getTestCaseName()).isEqualTo("TC2");
    }

    @Test
    @DisplayName("findByRunId respects LIMIT and OFFSET for pagination")
    void findByRunIdPagination() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Input Repo Pagination Suite")
                .getId();
        UUID runId = metaTestDataHelper.createTestSuiteRun(suiteId).getId();

        List<TestCaseRunInput> inputs = List.of(
                buildInput(runId, 0, "TC-0"),
                buildInput(runId, 1, "TC-1"),
                buildInput(runId, 2, "TC-2"),
                buildInput(runId, 3, "TC-3"),
                buildInput(runId, 4, "TC-4"));
        runInputRepository.insertBatch(inputs);

        // Page 1: first 3
        List<TestCaseRunInput> page1 = runInputRepository.findByRunId(runId, 0, 3);
        assertThat(page1).hasSize(3);
        assertThat(page1.get(0).getTestCaseName()).isEqualTo("TC-0");
        assertThat(page1.get(2).getTestCaseName()).isEqualTo("TC-2");

        // Page 2: next 3 (only 2 remain)
        List<TestCaseRunInput> page2 = runInputRepository.findByRunId(runId, 3, 3);
        assertThat(page2).hasSize(2);
        assertThat(page2.get(0).getTestCaseName()).isEqualTo("TC-3");
        assertThat(page2.get(1).getTestCaseName()).isEqualTo("TC-4");
    }

    @Test
    @DisplayName("existsByRunId returns true when inputs exist, false when none")
    void existsByRunId() {
        UUID suiteId =
                metaTestDataHelper.createTestSuite("Input Repo Exists Suite").getId();
        UUID runId = metaTestDataHelper.createTestSuiteRun(suiteId).getId();
        UUID emptyRunId = metaTestDataHelper.createTestSuiteRun(suiteId).getId();

        runInputRepository.insertBatch(List.of(buildInput(runId, 0, "TC-X")));

        assertThat(runInputRepository.existsByRunId(runId)).isTrue();
        assertThat(runInputRepository.existsByRunId(emptyRunId)).isFalse();
    }

    @Test
    @DisplayName("countByRunId returns accurate row count")
    void countByRunId() {
        UUID suiteId =
                metaTestDataHelper.createTestSuite("Input Repo Count Suite").getId();
        UUID runId = metaTestDataHelper.createTestSuiteRun(suiteId).getId();

        assertThat(runInputRepository.countByRunId(runId)).isEqualTo(0L);

        runInputRepository.insertBatch(
                List.of(buildInput(runId, 0, "TC-A"), buildInput(runId, 1, "TC-B"), buildInput(runId, 2, "TC-C")));

        assertThat(runInputRepository.countByRunId(runId)).isEqualTo(3L);
    }

    @Test
    @DisplayName("insertBatch is idempotent for overrides columns (null values stored correctly)")
    void insertBatchWithNullOverrides() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Input Repo Null Override Suite")
                .getId();
        UUID runId = metaTestDataHelper.createTestSuiteRun(suiteId).getId();

        TestCaseRunInput input = TestCaseRunInput.builder()
                .runId(runId)
                .position(0)
                .testCaseId(UUID.randomUUID())
                .testCaseName("TC-Null-Override")
                .testCaseData("{\"field\":\"value\"}")
                .requestTemplateOverride(null)
                .inputBindingsOverride(null)
                .build();

        runInputRepository.insertBatch(List.of(input));

        List<TestCaseRunInput> found = runInputRepository.findByRunId(runId, 0, 10);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getRequestTemplateOverride()).isNull();
        assertThat(found.get(0).getInputBindingsOverride()).isNull();
    }

    private TestCaseRunInput buildInput(UUID runId, int position, String name) {
        return TestCaseRunInput.builder()
                .runId(runId)
                .position(position)
                .testCaseId(UUID.randomUUID())
                .testCaseName(name)
                .testCaseData("{\"data\":\"" + name + "\"}")
                .build();
    }
}
