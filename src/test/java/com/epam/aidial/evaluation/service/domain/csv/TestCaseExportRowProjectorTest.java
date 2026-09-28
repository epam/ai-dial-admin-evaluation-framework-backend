package com.epam.aidial.evaluation.service.domain.csv;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.service.domain.csv.TestCaseExportRowProjector.ProjectedRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("TestCaseExportRowProjector")
class TestCaseExportRowProjectorTest {

    private final TestCaseExportRowProjector projector = new TestCaseExportRowProjector(new ObjectMapper());

    @Test
    @DisplayName("single-turn case yields one row with a blank turnIndex")
    void project_singleTurnCase_yieldsOneRowWithBlankTurnIndex() {
        TestCase testCase = TestCase.builder()
                .testCaseName("case-1")
                .data("{\"prompt\":\"hi\"}")
                .build();

        List<ProjectedRow> rows = projector.project(testCase);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().turnIndex()).isEmpty();
        assertThat(rows.getFirst().data()).containsEntry("prompt", "hi");
    }

    @Test
    @DisplayName("multi-turn case yields one row per turn with turnIndex 0..N-1 and shared data merged in")
    void project_multiTurnCase_yieldsOneRowPerTurnWithSharedDataMerged() {
        TestCase testCase = TestCase.builder()
                .testCaseName("case-2")
                .data("{\"shared\":\"s\"}")
                .multiTurnData("[{\"prompt\":\"t0\"},{\"prompt\":\"t1\"},{\"prompt\":\"t2\"}]")
                .build();

        List<ProjectedRow> rows = projector.project(testCase);

        assertThat(rows).hasSize(3);
        for (int i = 0; i < 3; i++) {
            ProjectedRow row = rows.get(i);
            assertThat(row.turnIndex()).isEqualTo(String.valueOf(i));
            assertThat(row.data()).containsEntry("shared", "s");
            assertThat(row.data()).containsEntry("prompt", "t" + i);
        }
    }

    @Test
    @DisplayName("per-turn value overrides a shared field of the same name")
    void project_perTurnValueSameNameAsShared_perTurnWins() {
        TestCase testCase = TestCase.builder()
                .testCaseName("case-3")
                .data("{\"field\":\"shared-value\"}")
                .multiTurnData("[{\"field\":\"turn-value\"}]")
                .build();

        List<ProjectedRow> rows = projector.project(testCase);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().data()).containsEntry("field", "turn-value");
    }

    @Test
    @DisplayName("blank data is projected as an empty map for a single-turn case")
    void project_blankData_yieldsEmptyMap() {
        TestCase testCase = TestCase.builder().testCaseName("case-4").data("").build();

        List<ProjectedRow> rows = projector.project(testCase);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().data()).isEqualTo(Map.of());
    }

    @Test
    @DisplayName("malformed multiTurnData JSON is treated as no turns, yielding no rows")
    void project_malformedMultiTurnData_yieldsNoRows() {
        TestCase testCase = TestCase.builder()
                .testCaseName("case-5")
                .data("{}")
                .multiTurnData("not-json")
                .build();

        List<ProjectedRow> rows = projector.project(testCase);

        assertThat(rows).isEmpty();
    }
}
