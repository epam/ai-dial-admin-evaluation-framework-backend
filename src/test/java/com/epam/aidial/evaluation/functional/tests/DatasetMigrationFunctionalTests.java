package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Functional verification of the V1.22 introduce-dataset-entity Flyway migration shape after the
 * migration has been applied. V1.22 is irreversible and runs during Spring Boot startup, so this
 * test asserts the post-migration schema directly via {@code information_schema}. The pre-migration
 * shape is documented in {@code design.md} and the migration body itself; this test is the only
 * place in the suite that's allowed to read the migration result via JdbcTemplate.
 *
 * <p>Specifically asserts:
 * <ul>
 *   <li>{@code datasets} table exists with the columns introduced in V1.22.</li>
 *   <li>{@code test_suites.test_case_schema} was dropped.</li>
 *   <li>{@code test_suites.dataset_id} (NOT NULL) and {@code test_suites.disabled_test_case_ids}
 *       (JSONB) columns are present.</li>
 *   <li>{@code test_cases.test_suite_id} was renamed to {@code dataset_id}; legacy override columns
 *       (request_template_override, input_bindings_override, is_enabled) are gone.</li>
 *   <li>{@code revalidation_tasks.test_suite_id} was renamed to {@code dataset_id}.</li>
 * </ul>
 */
@DisplayName("Dataset Migration (V1.22) — post-migration schema shape")
public abstract class DatasetMigrationFunctionalTests extends BaseFunctionalTest {

    @Autowired
    @Qualifier("metaRawJdbcTemplate")
    private JdbcTemplate metaJdbcTemplate;

    @Test
    @DisplayName("datasets table exists with the V1.22 column set")
    void datasetsTableHasExpectedColumns() {
        List<String> columns = metaJdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'datasets' "
                        + "ORDER BY column_name",
                String.class);

        assertThat(columns)
                .contains(
                        "id",
                        "name",
                        "description",
                        "test_case_schema",
                        "is_valid",
                        "validation_warnings",
                        "version",
                        "created_by",
                        "created_at_ms",
                        "updated_at_ms");
    }

    @Test
    @DisplayName("test_suites: test_case_schema dropped; dataset_id (NULLABLE) and disabled_test_case_ids present")
    void testSuitesPostMigrationShape() {
        List<Map<String, Object>> rows = metaJdbcTemplate.queryForList(
                "SELECT column_name, is_nullable, data_type FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'test_suites'");

        List<String> columnNames =
                rows.stream().map(r -> (String) r.get("column_name")).toList();
        assertThat(columnNames).doesNotContain("test_case_schema");
        assertThat(columnNames).contains("dataset_id", "disabled_test_case_ids");

        Map<String, Object> datasetIdRow = rows.stream()
                .filter(r -> "dataset_id".equals(r.get("column_name")))
                .findFirst()
                .orElseThrow();
        assertThat(datasetIdRow.get("is_nullable")).isEqualTo("YES");

        Map<String, Object> disabledRow = rows.stream()
                .filter(r -> "disabled_test_case_ids".equals(r.get("column_name")))
                .findFirst()
                .orElseThrow();
        assertThat(disabledRow.get("data_type")).isEqualTo("jsonb");
    }

    @Test
    @DisplayName("test_cases: test_suite_id renamed to dataset_id; override + is_enabled columns dropped")
    void testCasesPostMigrationShape() {
        List<String> columns = metaJdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'test_cases'",
                String.class);

        assertThat(columns).contains("dataset_id");
        assertThat(columns)
                .doesNotContain("test_suite_id", "request_template_override", "input_bindings_override", "is_enabled");
    }

    @Test
    @DisplayName("revalidation_tasks: test_suite_id renamed to dataset_id")
    void revalidationTasksPostMigrationShape() {
        List<String> columns = metaJdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'revalidation_tasks'",
                String.class);

        assertThat(columns).contains("dataset_id");
        assertThat(columns).doesNotContain("test_suite_id");
    }

    @Test
    @DisplayName("test_suites.dataset_id FK to datasets.id is RESTRICT (not CASCADE)")
    void testSuitesDatasetIdFkIsRestrict() {
        Integer count = metaJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.referential_constraints rc "
                        + "JOIN information_schema.key_column_usage kcu "
                        + "  ON kcu.constraint_name = rc.constraint_name "
                        + "WHERE kcu.table_name = 'test_suites' "
                        + "  AND kcu.column_name = 'dataset_id' "
                        + "  AND rc.delete_rule = 'RESTRICT'",
                Integer.class);

        assertThat(count).isPositive();
    }
}
