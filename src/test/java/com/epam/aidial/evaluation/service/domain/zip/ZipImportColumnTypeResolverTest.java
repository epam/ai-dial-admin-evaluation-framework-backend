package com.epam.aidial.evaluation.service.domain.zip;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.csv.CsvImportMode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ZipImportColumnTypeResolver")
class ZipImportColumnTypeResolverTest {

    private final ZipImportColumnTypeResolver resolver = new ZipImportColumnTypeResolver();

    private static FieldDefinitionDto field(String name, SchemaFieldType type) {
        return FieldDefinitionDto.builder().name(name).type(type).build();
    }

    @Test
    @DisplayName("OVERRIDE uses the manifest type, ignoring the dataset's current column type")
    void resolve_override_ignoresDatasetCurrentColumnType() {
        List<FieldDefinitionDto> datasetSchema = List.of(field("document", SchemaFieldType.STRING));
        List<FieldDefinitionDto> manifestSchema = List.of(field("document", SchemaFieldType.FILE));

        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> result =
                resolver.resolve(CsvImportMode.OVERRIDE, datasetSchema, manifestSchema, List.of("document"));

        assertThat(result.get("document").type()).isEqualTo(SchemaFieldType.FILE);
        assertThat(result.get("document").excluded()).isFalse();
        assertThat(result.get("document").eligibleForRewrite()).isTrue();
    }

    @Test
    @DisplayName("OVERRIDE without a manifest treats a currently STRING column as undetermined")
    void resolve_overrideWithoutManifest_treatsColumnAsUndetermined() {
        List<FieldDefinitionDto> datasetSchema = List.of(field("document", SchemaFieldType.STRING));

        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> result =
                resolver.resolve(CsvImportMode.OVERRIDE, datasetSchema, List.of(), List.of("document"));

        assertThat(result.get("document").type()).isNull();
        assertThat(result.get("document").eligibleForRewrite()).isTrue();
    }

    @Test
    @DisplayName("any mode into an empty dataset schema behaves like OVERRIDE (manifest type, else undetermined)")
    void resolve_emptyDatasetSchema_usesManifestTypeRegardlessOfMode() {
        List<FieldDefinitionDto> manifestSchema = List.of(field("document", SchemaFieldType.FILE));

        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> mergeResult =
                resolver.resolve(CsvImportMode.MERGE, List.of(), manifestSchema, List.of("document"));
        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> appendResult =
                resolver.resolve(CsvImportMode.APPEND, List.of(), manifestSchema, List.of("document"));

        assertThat(mergeResult.get("document").type()).isEqualTo(SchemaFieldType.FILE);
        assertThat(appendResult.get("document").type()).isEqualTo(SchemaFieldType.FILE);
    }

    @Test
    @DisplayName("MERGE prefers the dataset type, falling back to the manifest type for new columns")
    void resolve_merge_prefersDatasetTypeThenManifest() {
        List<FieldDefinitionDto> datasetSchema = List.of(field("document", SchemaFieldType.FILE));
        List<FieldDefinitionDto> manifestSchema =
                List.of(field("document", SchemaFieldType.STRING), field("attachment", SchemaFieldType.FILE));

        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> result =
                resolver.resolve(CsvImportMode.MERGE, datasetSchema, manifestSchema, List.of("document", "attachment"));

        // Existing dataset field wins over a conflicting manifest type.
        assertThat(result.get("document").type()).isEqualTo(SchemaFieldType.FILE);
        // New field not in the dataset schema falls back to the manifest.
        assertThat(result.get("attachment").type()).isEqualTo(SchemaFieldType.FILE);
        assertThat(result.get("attachment").excluded()).isFalse();
    }

    @Test
    @DisplayName("MERGE with neither a dataset nor a manifest type leaves the column undetermined")
    void resolve_merge_undeterminedWhenNeitherSourceHasType() {
        List<FieldDefinitionDto> datasetSchema = List.of(field("document", SchemaFieldType.FILE));

        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> result =
                resolver.resolve(CsvImportMode.MERGE, datasetSchema, List.of(), List.of("newColumn"));

        assertThat(result.get("newColumn").type()).isNull();
        assertThat(result.get("newColumn").eligibleForRewrite()).isTrue();
    }

    @Test
    @DisplayName("APPEND uses the dataset type only and excludes a column the dataset schema does not declare")
    void resolve_append_excludesUndeclaredColumn() {
        List<FieldDefinitionDto> datasetSchema = List.of(field("document", SchemaFieldType.FILE));
        List<FieldDefinitionDto> manifestSchema = List.of(field("attachment", SchemaFieldType.FILE));

        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> result = resolver.resolve(
                CsvImportMode.APPEND, datasetSchema, manifestSchema, List.of("document", "attachment"));

        assertThat(result.get("document").type()).isEqualTo(SchemaFieldType.FILE);
        assertThat(result.get("document").excluded()).isFalse();
        // Not in the dataset schema: excluded, regardless of what the manifest says.
        assertThat(result.get("attachment").excluded()).isTrue();
        assertThat(result.get("attachment").eligibleForRewrite()).isFalse();
    }

    @Test
    @DisplayName("APPEND leaves a STRING-typed dataset column ineligible for rewrite")
    void resolve_append_stringTypedColumnIsNotEligible() {
        List<FieldDefinitionDto> datasetSchema = List.of(field("notes", SchemaFieldType.STRING));

        Map<String, ZipImportColumnTypeResolver.ColumnTypeResolution> result =
                resolver.resolve(CsvImportMode.APPEND, datasetSchema, List.of(), List.of("notes"));

        assertThat(result.get("notes").type()).isEqualTo(SchemaFieldType.STRING);
        assertThat(result.get("notes").excluded()).isFalse();
        assertThat(result.get("notes").eligibleForRewrite()).isFalse();
    }
}
