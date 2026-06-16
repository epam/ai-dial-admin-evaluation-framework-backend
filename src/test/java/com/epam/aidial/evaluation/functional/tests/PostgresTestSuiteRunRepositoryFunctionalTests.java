package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.data.db.model.pagination.Page;
import com.epam.aidial.evaluation.data.db.model.pagination.PageRequest;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRunRepository;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("PostgresTestSuiteRunRepository tests")
public abstract class PostgresTestSuiteRunRepositoryFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private TestSuiteRunRepository runRepository;

    @Test
    @DisplayName("findAll excludes suite_snapshot column; suiteSnapshot field is null in list results")
    void findAllExcludesSuiteSnapshot() {
        UUID suiteId =
                metaTestDataHelper.createTestSuite("Repo Test Suite List").getId();
        TestSuiteRun saved = metaTestDataHelper.createTestSuiteRun(suiteId);

        // Set a snapshot so we can verify it's excluded from list queries
        runRepository.updateSuiteSnapshot(
                saved.getId(), "{\"snapshotVersion\":\"1\",\"suiteType\":\"DEPLOYMENT\"}", System.currentTimeMillis());

        Page<TestSuiteRun> page = runRepository.findAll(PageRequest.of(0, 100), List.of(), false);

        Optional<TestSuiteRun> found = page.getContent().stream()
                .filter(r -> r.getId().equals(saved.getId()))
                .findFirst();
        assertThat(found).isPresent();
        // List tier must NOT include suite_snapshot
        assertThat(found.get().getSuiteSnapshot()).isNull();
    }

    @Test
    @DisplayName("findById includes suite_snapshot column with stored snapshot JSON")
    void findByIdIncludesSuiteSnapshot() {
        UUID suiteId =
                metaTestDataHelper.createTestSuite("Repo Test Suite Detail").getId();
        TestSuiteRun saved = metaTestDataHelper.createTestSuiteRun(suiteId);
        String snapshotJson = "{\"snapshotVersion\":\"1\",\"suiteType\":\"DEPLOYMENT\"}";

        runRepository.updateSuiteSnapshot(saved.getId(), snapshotJson, System.currentTimeMillis());

        Optional<TestSuiteRun> found = runRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getSuiteSnapshot()).isNotNull();
        assertThat(found.get().getSuiteSnapshot()).contains("snapshotVersion");
    }

    @Test
    @DisplayName("findById returns null suiteSnapshot when no snapshot was set")
    void findByIdReturnsNullSuiteSnapshotWhenNotSet() {
        UUID suiteId = metaTestDataHelper
                .createTestSuite("Repo Test Suite No Snapshot")
                .getId();
        TestSuiteRun saved = metaTestDataHelper.createLegacyTestSuiteRun(suiteId);

        Optional<TestSuiteRun> found = runRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getSuiteSnapshot()).isNull();
    }

    @Test
    @DisplayName("updateSuiteSnapshot persists snapshot and is readable via findById")
    void updateSuiteSnapshotPersistsSnapshot() {
        UUID suiteId =
                metaTestDataHelper.createTestSuite("Repo Test Snapshot Update").getId();
        TestSuiteRun saved = metaTestDataHelper.createTestSuiteRun(suiteId);
        String snapshotJson = "{\"snapshotVersion\":\"1\",\"suiteType\":\"MCP_TOOL\"}";

        runRepository.updateSuiteSnapshot(saved.getId(), snapshotJson, System.currentTimeMillis());

        Optional<TestSuiteRun> found = runRepository.findById(saved.getId());
        assertThat(found).isPresent();
        // Snapshot is stored as JSONB — content should be equivalent
        assertThat(found.get().getSuiteSnapshot()).contains("MCP_TOOL");
    }
}
