package com.epam.aidial.evaluation.service.domain.csv;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportMode;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("ResolvedSchemaHints")
class ResolvedSchemaHintsTest {

    /** Dataset declares {@code shared} (STRING) and {@code untyped} (null type). */
    private static final List<FieldDefinitionDto> DATASET =
            List.of(field("shared", SchemaFieldType.STRING), field("untyped", null));

    /** Manifest declares {@code shared} as FILE and a new {@code extra} as INTEGER. */
    private static final CsvImportSchemaHints MANIFEST = new CsvImportSchemaHints(
            List.of(field("shared", SchemaFieldType.FILE), field("extra", SchemaFieldType.INTEGER)), Set.of("col"));

    /**
     * mode, dataset schema, column → expected drives / effectiveType / excludes. Columns: {@code shared}
     * (in dataset and manifest), {@code extra} (manifest only), {@code other} (neither).
     */
    static Stream<Arguments> table() {
        return Stream.of(
                // OVERRIDE: manifest decides every listed field; dataset types ignored.
                Arguments.of(CsvImportMode.OVERRIDE, DATASET, "shared", true, SchemaFieldType.FILE, false),
                Arguments.of(CsvImportMode.OVERRIDE, DATASET, "extra", true, SchemaFieldType.INTEGER, false),
                Arguments.of(CsvImportMode.OVERRIDE, DATASET, "other", false, null, false),
                // Any mode into an empty schema behaves like OVERRIDE.
                Arguments.of(CsvImportMode.APPEND, List.of(), "shared", true, SchemaFieldType.FILE, false),
                Arguments.of(CsvImportMode.MERGE, List.of(), "extra", true, SchemaFieldType.INTEGER, false),
                Arguments.of(CsvImportMode.APPEND, List.of(), "other", false, null, false),
                // MERGE into non-empty: dataset keeps its fields, manifest supplies only new ones.
                Arguments.of(CsvImportMode.MERGE, DATASET, "shared", false, SchemaFieldType.STRING, false),
                Arguments.of(CsvImportMode.MERGE, DATASET, "extra", true, SchemaFieldType.INTEGER, false),
                Arguments.of(CsvImportMode.MERGE, DATASET, "untyped", false, null, false),
                Arguments.of(CsvImportMode.MERGE, DATASET, "other", false, null, false),
                // APPEND into non-empty: manifest ignored, untyped/undeclared columns dropped.
                Arguments.of(CsvImportMode.APPEND, DATASET, "shared", false, SchemaFieldType.STRING, false),
                Arguments.of(CsvImportMode.APPEND, DATASET, "extra", false, null, true),
                Arguments.of(CsvImportMode.APPEND, DATASET, "untyped", false, null, true),
                Arguments.of(CsvImportMode.APPEND, DATASET, "other", false, null, true));
    }

    @ParameterizedTest(name = "{0} into {1} → {2}")
    @MethodSource("table")
    @DisplayName("resolves drives/effectiveType/excludes per the manifest-precedence table")
    void resolvesPerTable(
            CsvImportMode mode,
            List<FieldDefinitionDto> datasetSchema,
            String column,
            boolean drives,
            SchemaFieldType effectiveType,
            boolean excludes) {
        ResolvedSchemaHints hints = ResolvedSchemaHints.resolve(mode, datasetSchema, MANIFEST);

        assertThat(hints.drives(column)).as("drives").isEqualTo(drives);
        assertThat(hints.effectiveType(column)).as("effectiveType").isEqualTo(effectiveType);
        assertThat(hints.excludes(column)).as("excludes").isEqualTo(excludes);
    }

    @Test
    @DisplayName("fileColumns fallback applies only without manifest fields")
    void fileColumnsOnlyWithoutManifest() {
        CsvImportSchemaHints noManifest = new CsvImportSchemaHints(List.of(), Set.of("col"));

        assertThat(ResolvedSchemaHints.resolve(CsvImportMode.OVERRIDE, List.of(), noManifest)
                        .fileColumns())
                .containsExactly("col");
        assertThat(ResolvedSchemaHints.resolve(CsvImportMode.OVERRIDE, List.of(), MANIFEST)
                        .fileColumns())
                .isEmpty();
    }

    @Test
    @DisplayName("fileColumns fallback survives MERGE where no manifest field drives")
    void fileColumnsKeptWhenManifestAbsentInMerge() {
        CsvImportSchemaHints noManifest = new CsvImportSchemaHints(List.of(), Set.of("col"));

        ResolvedSchemaHints hints = ResolvedSchemaHints.resolve(CsvImportMode.MERGE, DATASET, noManifest);

        assertThat(hints.drivingFields()).isEmpty();
        assertThat(hints.fileColumns()).containsExactly("col");
    }

    @Test
    @DisplayName("plain CSV (EMPTY hints) has no driving fields and no file columns in every mode")
    void emptyHintsAreInert() {
        for (CsvImportMode mode : CsvImportMode.values()) {
            for (List<FieldDefinitionDto> schema : List.of(List.<FieldDefinitionDto>of(), DATASET)) {
                ResolvedSchemaHints hints = ResolvedSchemaHints.resolve(mode, schema, CsvImportSchemaHints.EMPTY);
                assertThat(hints.drivingFields())
                        .as("%s/%s", mode, schema.size())
                        .isEmpty();
                assertThat(hints.fileColumns()).as("%s/%s", mode, schema.size()).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("driving fields keep manifest order")
    void drivingFieldsKeepManifestOrder() {
        ResolvedSchemaHints hints = ResolvedSchemaHints.resolve(CsvImportMode.OVERRIDE, DATASET, MANIFEST);

        assertThat(hints.drivingFields().keySet()).containsExactly("shared", "extra");
    }

    private static FieldDefinitionDto field(String name, SchemaFieldType type) {
        return FieldDefinitionDto.builder().name(name).type(type).build();
    }
}
