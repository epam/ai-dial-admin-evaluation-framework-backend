package com.epam.aidial.evaluation.service.domain.csv;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CsvSchemaFieldBuilder")
class CsvSchemaFieldBuilderTest {

    private final CsvSchemaFieldBuilder builder = new CsvSchemaFieldBuilder();

    private static ColumnBinding dataBinding(String name) {
        return new ColumnBinding(name, "data", name);
    }

    private static FieldDefinitionDto currentField(String name, boolean perTurn) {
        return FieldDefinitionDto.builder()
                .name(name)
                .type(SchemaFieldType.STRING)
                .required(false)
                .perTurn(perTurn ? Boolean.TRUE : null)
                .build();
    }

    // -------------------------------------------------------------------------
    // buildFromBindings — perTurn carry-forward
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("buildFromBindings")
    class BuildFromBindings {

        @Test
        @DisplayName("carries perTurn forward by field name from the current schema")
        void carriesPerTurnForward() {
            List<ColumnBinding> bindings = List.of(dataBinding("prompt"));
            List<FieldDefinitionDto> currentSchema = List.of(currentField("prompt", true));

            List<FieldDefinitionDto> result = builder.buildFromBindings(bindings, null, currentSchema, Set.of());

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getPerTurn()).isTrue();
        }

        @Test
        @DisplayName("a column with no same-named current field gets perTurn absent")
        void unknownColumnHasNoPerTurn() {
            List<ColumnBinding> bindings = List.of(dataBinding("newField"));
            List<FieldDefinitionDto> currentSchema = List.of(currentField("prompt", true));

            List<FieldDefinitionDto> result = builder.buildFromBindings(bindings, null, currentSchema, Set.of());

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getPerTurn()).isNull();
        }

        @Test
        @DisplayName("null current schema — every field gets perTurn absent")
        void nullCurrentSchemaHandled() {
            List<ColumnBinding> bindings = List.of(dataBinding("prompt"));

            List<FieldDefinitionDto> result = builder.buildFromBindings(bindings, null, null, Set.of());

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getPerTurn()).isNull();
        }

        @Test
        @DisplayName("empty current schema — every field gets perTurn absent")
        void emptyCurrentSchemaHandled() {
            List<ColumnBinding> bindings = List.of(dataBinding("prompt"));

            List<FieldDefinitionDto> result = builder.buildFromBindings(bindings, null, List.of(), Set.of());

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getPerTurn()).isNull();
        }

        @Test
        @DisplayName("null types — every field gets type absent (validation-time, unknown types)")
        void nullTypesLeavesTypeNull() {
            List<ColumnBinding> bindings = List.of(dataBinding("prompt"));

            List<FieldDefinitionDto> result = builder.buildFromBindings(bindings, null, List.of(), Set.of());

            assertThat(result.getFirst().getType()).isNull();
        }

        @Test
        @DisplayName("supplied types resolve the field's type; a binding absent from the map defaults to STRING")
        void suppliedTypesResolveType() {
            List<ColumnBinding> bindings = List.of(dataBinding("score"), dataBinding("label"));
            Map<String, SchemaFieldType> types = Map.of("score", SchemaFieldType.INTEGER);

            List<FieldDefinitionDto> result = builder.buildFromBindings(bindings, types, List.of(), Set.of());

            assertThat(result)
                    .extracting(FieldDefinitionDto::getName, FieldDefinitionDto::getType)
                    .containsExactly(
                            Tuple.tuple("score", SchemaFieldType.INTEGER),
                            Tuple.tuple("label", SchemaFieldType.STRING));
        }

        @Test
        @DisplayName("non-data bindings (testCaseName, turnIndex) are excluded")
        void nonDataBindingsExcluded() {
            List<ColumnBinding> bindings = List.of(
                    new ColumnBinding("testCaseName", "testCaseName", "testCaseName"),
                    new ColumnBinding("turnIndex", "turnIndex", "turnIndex"),
                    dataBinding("prompt"));

            List<FieldDefinitionDto> result = builder.buildFromBindings(bindings, null, List.of(), Set.of());

            assertThat(result).extracting(FieldDefinitionDto::getName).containsExactly("prompt");
        }

        @Test
        @DisplayName("never mutates the current schema's field instances, and never returns them")
        void doesNotMutateOrReturnInputInstances() {
            FieldDefinitionDto original = currentField("prompt", true);
            List<FieldDefinitionDto> currentSchema = List.of(original);
            List<ColumnBinding> bindings = List.of(dataBinding("prompt"));

            List<FieldDefinitionDto> result = builder.buildFromBindings(bindings, null, currentSchema, Set.of());

            // input untouched
            assertThat(original.getPerTurn()).isTrue();
            assertThat(original.getType()).isEqualTo(SchemaFieldType.STRING);
            // output contains no instance identical to the input
            assertThat(result).noneMatch(f -> f == original);
        }

        // ---------------------------------------------------------------------
        // multiTurnColumns — three-tier scope resolution (design D1)
        // ---------------------------------------------------------------------

        @Test
        @DisplayName("declared per-turn field beats membership: stays per-turn even if also in multiTurnColumns")
        void declaredPerTurnBeatsMembership() {
            List<ColumnBinding> bindings = List.of(dataBinding("prompt"));
            List<FieldDefinitionDto> currentSchema = List.of(currentField("prompt", true));

            List<FieldDefinitionDto> result =
                    builder.buildFromBindings(bindings, null, currentSchema, Set.of("prompt"));

            assertThat(result.getFirst().getPerTurn()).isTrue();
        }

        @Test
        @DisplayName("declared-shared field beats membership — the containsKey regression guard: a declared "
                + "field with absent perTurn stays shared even when its name is also in multiTurnColumns")
        void declaredSharedBeatsMembership() {
            List<ColumnBinding> bindings = List.of(dataBinding("prompt"));
            List<FieldDefinitionDto> currentSchema = List.of(currentField("prompt", false));

            List<FieldDefinitionDto> result =
                    builder.buildFromBindings(bindings, null, currentSchema, Set.of("prompt"));

            assertThat(result.getFirst().getPerTurn()).isNull();
        }

        @Test
        @DisplayName("undeclared field in multiTurnColumns resolves to perTurn=true")
        void undeclaredMemberResolvesToPerTurnTrue() {
            List<ColumnBinding> bindings = List.of(dataBinding("history"));

            List<FieldDefinitionDto> result = builder.buildFromBindings(bindings, null, List.of(), Set.of("history"));

            assertThat(result.getFirst().getPerTurn()).isTrue();
        }

        @Test
        @DisplayName("undeclared field NOT in multiTurnColumns resolves to perTurn absent")
        void undeclaredNonMemberResolvesToPerTurnAbsent() {
            List<ColumnBinding> bindings = List.of(dataBinding("history"));

            List<FieldDefinitionDto> result =
                    builder.buildFromBindings(bindings, null, List.of(), Set.of("otherField"));

            assertThat(result.getFirst().getPerTurn()).isNull();
        }

        @Test
        @DisplayName("empty multiTurnColumns set reproduces today's output exactly — every undeclared field "
                + "stays shared")
        void emptyMembershipSetReproducesTodaysOutput() {
            List<ColumnBinding> bindings = List.of(dataBinding("prompt"), dataBinding("history"));
            List<FieldDefinitionDto> currentSchema = List.of(currentField("prompt", true));

            List<FieldDefinitionDto> result = builder.buildFromBindings(bindings, null, currentSchema, Set.of());

            assertThat(result)
                    .extracting(FieldDefinitionDto::getName, FieldDefinitionDto::getPerTurn)
                    .containsExactly(Tuple.tuple("prompt", true), Tuple.tuple("history", null));
        }
    }

    // -------------------------------------------------------------------------
    // buildMergeDelta — MERGE delta with perTurn carry-forward
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("buildMergeDelta")
    class BuildMergeDelta {

        @Test
        @DisplayName("returns only columns absent from the current schema")
        void returnsOnlyNewColumns() {
            List<FieldDefinitionDto> currentSchema = List.of(currentField("prompt", false));
            List<ColumnBinding> bindings = List.of(dataBinding("prompt"), dataBinding("newField"));

            List<FieldDefinitionDto> delta = builder.buildMergeDelta(currentSchema, bindings, null, Set.of());

            assertThat(delta).extracting(FieldDefinitionDto::getName).containsExactly("newField");
        }

        @Test
        @DisplayName("a delta field is always new to the current schema by definition, so perTurn is absent "
                + "when it is not in multiTurnColumns")
        void deltaFieldsAlwaysLackPerTurn() {
            List<FieldDefinitionDto> currentSchema =
                    List.of(currentField("prompt", false), currentField("history", true));
            List<ColumnBinding> bindings =
                    List.of(dataBinding("prompt"), dataBinding("history"), dataBinding("newField"));

            List<FieldDefinitionDto> delta = builder.buildMergeDelta(currentSchema, bindings, null, Set.of());

            assertThat(delta).extracting(FieldDefinitionDto::getName).containsExactly("newField");
            assertThat(delta.getFirst().getPerTurn()).isNull();
        }

        @Test
        @DisplayName("null current schema — every binding is new, perTurn absent")
        void nullCurrentSchemaHandled() {
            List<ColumnBinding> bindings = List.of(dataBinding("prompt"));

            List<FieldDefinitionDto> delta = builder.buildMergeDelta(null, bindings, null, Set.of());

            assertThat(delta).hasSize(1);
            assertThat(delta.getFirst().getPerTurn()).isNull();
        }

        @Test
        @DisplayName("supplied types resolve the delta field's type; absent from the map defaults to STRING")
        void suppliedTypesResolveType() {
            List<ColumnBinding> bindings = List.of(dataBinding("score"));
            Map<String, SchemaFieldType> types = Map.of("score", SchemaFieldType.INTEGER);

            List<FieldDefinitionDto> delta = builder.buildMergeDelta(List.of(), bindings, types, Set.of());

            assertThat(delta.getFirst().getType()).isEqualTo(SchemaFieldType.INTEGER);
        }

        @Test
        @DisplayName("never mutates the current schema's field instances, and never returns them")
        void doesNotMutateOrReturnInputInstances() {
            FieldDefinitionDto original = currentField("prompt", true);
            List<FieldDefinitionDto> currentSchema = List.of(original);
            List<ColumnBinding> bindings = List.of(dataBinding("prompt"), dataBinding("newField"));

            List<FieldDefinitionDto> delta = builder.buildMergeDelta(currentSchema, bindings, null, Set.of());

            assertThat(original.getPerTurn()).isTrue();
            assertThat(delta).noneMatch(f -> f == original);
        }

        // ---------------------------------------------------------------------
        // multiTurnColumns — delta fields are always undeclared, so tier 2/3 apply directly
        // ---------------------------------------------------------------------

        @Test
        @DisplayName("delta field whose name is in multiTurnColumns resolves to perTurn=true")
        void deltaFieldInMembershipResolvesToPerTurnTrue() {
            List<FieldDefinitionDto> currentSchema = List.of(currentField("prompt", false));
            List<ColumnBinding> bindings = List.of(dataBinding("prompt"), dataBinding("newField"));

            List<FieldDefinitionDto> delta = builder.buildMergeDelta(currentSchema, bindings, null, Set.of("newField"));

            assertThat(delta.getFirst().getPerTurn()).isTrue();
        }

        @Test
        @DisplayName("empty multiTurnColumns set reproduces today's output exactly — delta fields stay shared")
        void emptyMembershipSetReproducesTodaysOutput() {
            List<FieldDefinitionDto> currentSchema = List.of(currentField("prompt", false));
            List<ColumnBinding> bindings = List.of(dataBinding("prompt"), dataBinding("newField"));

            List<FieldDefinitionDto> delta = builder.buildMergeDelta(currentSchema, bindings, null, Set.of());

            assertThat(delta.getFirst().getPerTurn()).isNull();
        }
    }
}
