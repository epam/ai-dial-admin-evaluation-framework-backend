package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DatasetSchemaFieldIdResolverTest {

    private final DatasetSchemaFieldIdResolver resolver = new DatasetSchemaFieldIdResolver();

    private static FieldDefinitionDto field(String id, String name) {
        return FieldDefinitionDto.builder()
                .id(id)
                .name(name)
                .type(SchemaFieldType.STRING)
                .build();
    }

    @Test
    @DisplayName("create assigns a fresh UUID to every field and does not mutate input")
    void createAssignsIds() {
        final List<FieldDefinitionDto> in = List.of(field(null, "a"), field(null, "b"));

        final SchemaFieldResolution r = resolver.resolveForCreate(in);

        assertThat(r.schema()).hasSize(2).allSatisfy(x -> UUID.fromString(x.getId()));
        assertThat(r.schema().get(0).getId()).isNotEqualTo(r.schema().get(1).getId());
        assertThat(r.renames()).isEmpty();
        assertThat(r.removedNames()).isEmpty();
        assertThat(in.get(0).getId()).isNull();
    }

    @Test
    @DisplayName("create rejects a client-supplied id")
    void createRejectsId() {
        assertThatThrownBy(() -> resolver.resolveForCreate(List.of(field("A", "a"))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("create rejects case-insensitive duplicate names")
    void createRejectsDuplicateNames() {
        assertThatThrownBy(() -> resolver.resolveForCreate(List.of(field(null, "x"), field(null, "X"))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("update keeps explicit ids and reports no renames or removals")
    void updateKeepsIds() {
        final SchemaFieldResolution r = resolver.resolveForUpdate(
                List.of(field("A", "a"), field("B", "b")), List.of(field("B", "b"), field("A", "a")));

        assertThat(r.schema()).extracting(FieldDefinitionDto::getId).containsExactly("B", "A");
        assertThat(r.renames()).isEmpty();
        assertThat(r.removedNames()).isEmpty();
    }

    @Test
    @DisplayName("field without id is matched by exact name and keeps the stored id")
    void nameFallback() {
        final SchemaFieldResolution r =
                resolver.resolveForUpdate(List.of(field("A", "prompt")), List.of(field(null, "prompt")));

        assertThat(r.schema().get(0).getId()).isEqualTo("A");
        assertThat(r.removedNames()).isEmpty();
    }

    @Test
    @DisplayName("name fallback is case-sensitive: differently cased name is a new field and old one removed")
    void nameFallbackCaseSensitive() {
        final SchemaFieldResolution r =
                resolver.resolveForUpdate(List.of(field("A", "prompt")), List.of(field(null, "Prompt")));

        assertThat(r.schema().get(0).getId()).isNotEqualTo("A");
        assertThat(r.removedNames()).containsExactly("prompt");
    }

    @Test
    @DisplayName("name match does not steal an id claimed explicitly by another entry")
    void claimedIdGuard() {
        final SchemaFieldResolution r =
                resolver.resolveForUpdate(List.of(field("A", "a")), List.of(field("A", "b"), field(null, "a")));

        assertThat(r.schema().get(0).getId()).isEqualTo("A");
        assertThat(r.schema().get(1).getId()).isNotEqualTo("A");
        assertThat(r.renames()).isEqualTo(Map.of("a", "b"));
        assertThat(r.removedNames()).isEmpty();
    }

    @Test
    @DisplayName("rename is detected when id is kept with a different name")
    void renameDetection() {
        final SchemaFieldResolution r = resolver.resolveForUpdate(
                List.of(field("A", "a"), field("B", "b")), List.of(field("A", "a"), field("B", "c")));

        assertThat(r.renames()).isEqualTo(Map.of("b", "c"));
        assertThat(r.removedNames()).isEmpty();
    }

    @Test
    @DisplayName("swap a<->b yields both renames")
    void swap() {
        final SchemaFieldResolution r = resolver.resolveForUpdate(
                List.of(field("A", "a"), field("B", "b")), List.of(field("A", "b"), field("B", "a")));

        assertThat(r.renames()).isEqualTo(Map.of("a", "b", "b", "a"));
        assertThat(r.removedNames()).isEmpty();
    }

    @Test
    @DisplayName("chain a->b, b->c yields both renames")
    void chain() {
        final SchemaFieldResolution r = resolver.resolveForUpdate(
                List.of(field("A", "a"), field("B", "b")), List.of(field("A", "b"), field("B", "c")));

        assertThat(r.renames()).isEqualTo(Map.of("a", "b", "b", "c"));
        assertThat(r.removedNames()).isEmpty();
    }

    @Test
    @DisplayName("removed names exclude renamed fields")
    void removedExcludesRenamed() {
        final SchemaFieldResolution r = resolver.resolveForUpdate(
                List.of(field("A", "a"), field("B", "b"), field("C", "c")), List.of(field("A", "z"), field("C", "c")));

        assertThat(r.renames()).isEqualTo(Map.of("a", "z"));
        assertThat(r.removedNames()).containsExactly("b");
    }

    @Test
    @DisplayName("stored id-less field matched by name gets an id and is not removed")
    void storedIdLessField() {
        final SchemaFieldResolution r =
                resolver.resolveForUpdate(List.of(field(null, "prompt")), List.of(field(null, "prompt")));

        assertThat(r.schema().get(0).getId()).isNotNull();
        assertThat(r.removedNames()).isEmpty();
        assertThat(r.renames()).isEmpty();
    }

    @Test
    @DisplayName("update rejects an unknown id")
    void unknownId() {
        assertThatThrownBy(() -> resolver.resolveForUpdate(List.of(field("A", "a")), List.of(field("Z", "a"))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("update rejects a duplicate id")
    void duplicateId() {
        assertThatThrownBy(() ->
                        resolver.resolveForUpdate(List.of(field("A", "a")), List.of(field("A", "a"), field("A", "b"))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("update rejects case-insensitive duplicate names")
    void duplicateNames() {
        assertThatThrownBy(() -> resolver.resolveForUpdate(
                        List.of(field("A", "x"), field("B", "y")), List.of(field("A", "x"), field("B", "X"))))
                .isInstanceOf(ValidationException.class);
    }
}
