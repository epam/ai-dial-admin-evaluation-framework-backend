package com.epam.aidial.evaluation.service.domain.csv;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.TestCaseFieldScopeResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("MultiTurnRunAssembler")
class MultiTurnRunAssemblerTest {

    private final MultiTurnRunAssembler assembler = new MultiTurnRunAssembler(new TestCaseFieldScopeResolver());

    private static FieldDefinitionDto field(String name, boolean perTurn) {
        return FieldDefinitionDto.builder()
                .name(name)
                .type(SchemaFieldType.STRING)
                .required(false)
                .perTurn(perTurn ? Boolean.TRUE : null)
                .build();
    }

    private static ParsedCsvRow row(int rowNumber, String name, Integer turnIndex, Map<String, Object> data) {
        return row(rowNumber, name, turnIndex, data, false);
    }

    private static ParsedCsvRow row(
            int rowNumber, String name, Integer turnIndex, Map<String, Object> data, boolean hasJsonParseErrors) {
        return new ParsedCsvRow(rowNumber, name, turnIndex, data, hasJsonParseErrors);
    }

    private static CsvTestCase run(ParsedCsvRow... rows) {
        List<ParsedCsvRow> list = List.of(rows);
        return new CsvTestCase(
                list, list.getFirst().testCaseName(), list.getFirst().rowNumber(), true, false);
    }

    @Nested
    @DisplayName("turn ordering")
    class TurnOrdering {

        @Test
        @DisplayName("orders per-turn maps by turnIndex regardless of CSV row order")
        void ordersByTurnIndex() {
            CsvTestCase csvTestCase =
                    run(row(3, "conv", 1, Map.of("prompt", "second")), row(2, "conv", 0, Map.of("prompt", "first")));
            List<FieldDefinitionDto> schema = List.of(field("prompt", true));

            MultiTurnAssembly assembly = assembler.assemble(csvTestCase, schema);

            assertThat(assembly.perTurnMaps()).extracting(m -> m.get("prompt")).containsExactly("first", "second");
        }

        @Test
        @DisplayName("a null turnIndex (unparseable cell) sorts last, stable by row order")
        void nullTurnIndexSortsLast() {
            CsvTestCase csvTestCase =
                    run(row(2, "conv", null, Map.of("prompt", "blank")), row(3, "conv", 0, Map.of("prompt", "zero")));
            List<FieldDefinitionDto> schema = List.of(field("prompt", true));

            MultiTurnAssembly assembly = assembler.assemble(csvTestCase, schema);

            assertThat(assembly.perTurnMaps()).extracting(m -> m.get("prompt")).containsExactly("zero", "blank");
        }
    }

    @Nested
    @DisplayName("scope partitioning")
    class ScopePartitioning {

        @Test
        @DisplayName("perTurn=true fields go to each turn's map, others go to the shared map")
        void splitsBySchemaScope() {
            CsvTestCase csvTestCase = run(
                    row(2, "conv", 0, Map.of("prompt", "hello", "topic", "chat")),
                    row(3, "conv", 1, Map.of("prompt", "world", "topic", "chat")));
            List<FieldDefinitionDto> schema = List.of(field("prompt", true), field("topic", false));

            MultiTurnAssembly assembly = assembler.assemble(csvTestCase, schema);

            assertThat(assembly.sharedData()).containsEntry("topic", "chat").doesNotContainKey("prompt");
            assertThat(assembly.perTurnMaps().get(0))
                    .containsEntry("prompt", "hello")
                    .doesNotContainKey("topic");
            assertThat(assembly.perTurnMaps().get(1)).containsEntry("prompt", "world");
        }

        @Test
        @DisplayName("a column with no matching schema field is routed to the shared map")
        void unknownColumnRoutedToShared() {
            CsvTestCase csvTestCase = run(row(2, "conv", 0, Map.of("mystery", "x")));
            List<FieldDefinitionDto> schema = List.of();

            MultiTurnAssembly assembly = assembler.assemble(csvTestCase, schema);

            assertThat(assembly.sharedData()).containsEntry("mystery", "x");
            assertThat(assembly.perTurnMaps().getFirst()).isEmpty();
        }
    }

    @Nested
    @DisplayName("conflict detection")
    class ConflictDetection {

        @Test
        @DisplayName("differing shared-column values across turns are flagged as a shared conflict")
        void sharedColumnMismatchDetected() {
            CsvTestCase csvTestCase =
                    run(row(2, "conv", 0, Map.of("topic", "a")), row(3, "conv", 1, Map.of("topic", "b")));
            List<FieldDefinitionDto> schema = List.of(field("topic", false));

            MultiTurnAssembly assembly = assembler.assemble(csvTestCase, schema);

            assertThat(assembly.sharedConflict()).isTrue();
        }

        @Test
        @DisplayName("identical shared-column values across turns are not flagged")
        void identicalSharedColumnsNotFlagged() {
            CsvTestCase csvTestCase =
                    run(row(2, "conv", 0, Map.of("topic", "a")), row(3, "conv", 1, Map.of("topic", "a")));
            List<FieldDefinitionDto> schema = List.of(field("topic", false));

            MultiTurnAssembly assembly = assembler.assemble(csvTestCase, schema);

            assertThat(assembly.sharedConflict()).isFalse();
        }

        @Test
        @DisplayName("a duplicated turnIndex within the run is detected")
        void duplicateTurnIndexDetected() {
            CsvTestCase csvTestCase =
                    run(row(2, "dup", 0, Map.of("prompt", "a")), row(3, "dup", 0, Map.of("prompt", "b")));
            List<FieldDefinitionDto> schema = List.of(field("prompt", true));

            MultiTurnAssembly assembly = assembler.assemble(csvTestCase, schema);

            assertThat(assembly.duplicateTurnIndex()).isTrue();
        }

        @Test
        @DisplayName("distinct turnIndex values within the run are not flagged as duplicate")
        void distinctTurnIndexNotFlagged() {
            CsvTestCase csvTestCase =
                    run(row(2, "conv", 0, Map.of("prompt", "a")), row(3, "conv", 1, Map.of("prompt", "b")));
            List<FieldDefinitionDto> schema = List.of(field("prompt", true));

            MultiTurnAssembly assembly = assembler.assemble(csvTestCase, schema);

            assertThat(assembly.duplicateTurnIndex()).isFalse();
        }

        @Test
        @DisplayName("a JSON parse error on any row surfaces on the assembly")
        void jsonParseErrorSurfaced() {
            CsvTestCase csvTestCase = run(
                    row(2, "conv", 0, Map.of("payload", "not-json"), true),
                    row(3, "conv", 1, Map.of("payload", "{}"), false));
            List<FieldDefinitionDto> schema = List.of(field("payload", true));

            MultiTurnAssembly assembly = assembler.assemble(csvTestCase, schema);

            assertThat(assembly.hasJsonParseErrors()).isTrue();
        }

        @Test
        @DisplayName("no JSON parse error on any row leaves the flag false")
        void noJsonParseErrorFlagFalse() {
            CsvTestCase csvTestCase = run(row(2, "conv", 0, Map.of("prompt", "a")));
            List<FieldDefinitionDto> schema = List.of(field("prompt", true));

            MultiTurnAssembly assembly = assembler.assemble(csvTestCase, schema);

            assertThat(assembly.hasJsonParseErrors()).isFalse();
        }
    }
}
