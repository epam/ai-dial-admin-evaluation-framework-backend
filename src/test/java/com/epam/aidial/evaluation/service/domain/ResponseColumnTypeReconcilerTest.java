package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.exception.TypeMismatchException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResponseColumnTypeReconcilerTest {

    private final ResponseColumnTypeReconciler reconciler = new ResponseColumnTypeReconciler();

    @Test
    @DisplayName("ARRAY + scalar wraps to singleton list")
    void arrayWrapsScalarToSingleton() {
        Object result = reconciler.reconcile("DECATHLON_map.pdf#page=1", SchemaFieldType.ARRAY);
        assertThat(result).isEqualTo(List.of("DECATHLON_map.pdf#page=1"));
    }

    @Test
    @DisplayName("ARRAY + null returns null")
    void arrayWithNullReturnsNull() {
        assertThat(reconciler.reconcile(null, SchemaFieldType.ARRAY)).isNull();
    }

    @Test
    @DisplayName("ARRAY + existing list returns it as-is")
    void arrayWithListReturnsAsIs() {
        List<String> input = List.of("a", "b");
        assertThat(reconciler.reconcile(input, SchemaFieldType.ARRAY)).isSameAs(input);
    }

    @Test
    @DisplayName("ARRAY + object wraps to singleton list")
    void arrayWithObjectWrapsToSingleton() {
        Map<String, Object> obj = Map.of("k", "v");
        Object result = reconciler.reconcile(obj, SchemaFieldType.ARRAY);
        assertThat(result).isEqualTo(List.of(obj));
    }

    @Test
    @DisplayName("STRING + number coerces via String.valueOf")
    void stringFromNumberCoerces() {
        assertThat(reconciler.reconcile(42L, SchemaFieldType.STRING)).isEqualTo("42");
        assertThat(reconciler.reconcile(3.14, SchemaFieldType.STRING)).isEqualTo("3.14");
    }

    @Test
    @DisplayName("STRING + boolean coerces via String.valueOf")
    void stringFromBooleanCoerces() {
        assertThat(reconciler.reconcile(true, SchemaFieldType.STRING)).isEqualTo("true");
    }

    @Test
    @DisplayName("STRING + array throws TypeMismatchException")
    void stringFromArrayThrows() {
        assertThatThrownBy(() -> reconciler.reconcile(List.of("a"), SchemaFieldType.STRING))
                .isInstanceOf(TypeMismatchException.class)
                .hasMessage("Type mismatch: expected STRING, got ARRAY");
    }

    @Test
    @DisplayName("STRING + object throws TypeMismatchException")
    void stringFromObjectThrows() {
        assertThatThrownBy(() -> reconciler.reconcile(Map.of("k", "v"), SchemaFieldType.STRING))
                .isInstanceOf(TypeMismatchException.class)
                .hasMessage("Type mismatch: expected STRING, got OBJECT");
    }

    @Test
    @DisplayName("FILE behaves like STRING")
    void fileBehavesLikeString() {
        assertThat(reconciler.reconcile("file.pdf", SchemaFieldType.FILE)).isEqualTo("file.pdf");
        assertThatThrownBy(() -> reconciler.reconcile(List.of("file.pdf"), SchemaFieldType.FILE))
                .isInstanceOf(TypeMismatchException.class)
                .hasMessage("Type mismatch: expected FILE, got ARRAY");
    }

    @Test
    @DisplayName("INTEGER + whole-valued Double normalizes to Long")
    void integerFromWholeDoubleNormalizes() {
        Object result = reconciler.reconcile(42.0, SchemaFieldType.INTEGER);
        assertThat(result).isEqualTo(42L);
    }

    @Test
    @DisplayName("INTEGER + Long passes through")
    void integerFromLongPassesThrough() {
        assertThat(reconciler.reconcile(42L, SchemaFieldType.INTEGER)).isEqualTo(42L);
    }

    @Test
    @DisplayName("INTEGER + fractional Double throws with NUMBER label")
    void integerFromFractionalDoubleThrows() {
        assertThatThrownBy(() -> reconciler.reconcile(3.14, SchemaFieldType.INTEGER))
                .isInstanceOf(TypeMismatchException.class)
                .hasMessageContaining("expected INTEGER, got NUMBER")
                .hasMessageContaining("3.14")
                .hasMessageContaining("fractional value not representable as integer");
    }

    @Test
    @DisplayName("INTEGER + parseable string coerces to Long")
    void integerFromParseableStringCoerces() {
        assertThat(reconciler.reconcile("42", SchemaFieldType.INTEGER)).isEqualTo(42L);
    }

    @Test
    @DisplayName("INTEGER + non-parseable string throws with truncated value preview")
    void integerFromNonParseableStringThrowsWithPreview() {
        assertThatThrownBy(() -> reconciler.reconcile("abc", SchemaFieldType.INTEGER))
                .isInstanceOf(TypeMismatchException.class)
                .hasMessage("Type mismatch: expected INTEGER, got STRING (\"abc\") — not parseable as integer");
    }

    @Test
    @DisplayName("INTEGER + very long non-parseable string truncates value preview to 80 chars")
    void integerLongValuePreviewTruncates() {
        String longValue = "x".repeat(200);
        assertThatThrownBy(() -> reconciler.reconcile(longValue, SchemaFieldType.INTEGER))
                .isInstanceOf(TypeMismatchException.class)
                .satisfies(ex -> {
                    String msg = ex.getMessage();
                    assertThat(msg).contains("expected INTEGER, got STRING");
                    assertThat(msg).contains("\"" + "x".repeat(80) + "\"");
                    assertThat(msg).doesNotContain("\"" + "x".repeat(81) + "\"");
                });
    }

    @Test
    @DisplayName("NUMBER + parseable string coerces to Double")
    void numberFromParseableStringCoerces() {
        assertThat(reconciler.reconcile("3.14", SchemaFieldType.NUMBER)).isEqualTo(3.14);
    }

    @Test
    @DisplayName("NUMBER + Long converts to Double")
    void numberFromLongConvertsToDouble() {
        assertThat(reconciler.reconcile(42L, SchemaFieldType.NUMBER)).isEqualTo(42.0);
    }

    @Test
    @DisplayName("NUMBER + boolean throws")
    void numberFromBooleanThrows() {
        assertThatThrownBy(() -> reconciler.reconcile(true, SchemaFieldType.NUMBER))
                .isInstanceOf(TypeMismatchException.class)
                .hasMessage("Type mismatch: expected NUMBER, got BOOLEAN");
    }

    @Test
    @DisplayName("BOOLEAN + \"true\"/\"false\" string parses (case-insensitive)")
    void booleanFromStringCaseInsensitive() {
        assertThat(reconciler.reconcile("true", SchemaFieldType.BOOLEAN)).isEqualTo(true);
        assertThat(reconciler.reconcile("FALSE", SchemaFieldType.BOOLEAN)).isEqualTo(false);
        assertThat(reconciler.reconcile("True", SchemaFieldType.BOOLEAN)).isEqualTo(true);
    }

    @Test
    @DisplayName("BOOLEAN + non-boolean string throws")
    void booleanFromNonBooleanStringThrows() {
        assertThatThrownBy(() -> reconciler.reconcile("yes", SchemaFieldType.BOOLEAN))
                .isInstanceOf(TypeMismatchException.class)
                .hasMessageContaining("expected BOOLEAN, got STRING")
                .hasMessageContaining("not parseable as boolean");
    }

    @Test
    @DisplayName("OBJECT + scalar throws")
    void objectFromScalarThrows() {
        assertThatThrownBy(() -> reconciler.reconcile("hi", SchemaFieldType.OBJECT))
                .isInstanceOf(TypeMismatchException.class)
                .hasMessage("Type mismatch: expected OBJECT, got STRING");
    }

    @Test
    @DisplayName("OBJECT + map returns as-is")
    void objectFromMapReturnsAsIs() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("k", "v");
        assertThat(reconciler.reconcile(obj, SchemaFieldType.OBJECT)).isSameAs(obj);
    }

    @Test
    @DisplayName("Declared type null passes value through unchanged")
    void declaredTypeNullPassesThrough() {
        Object input = "anything";
        assertThat(reconciler.reconcile(input, null)).isSameAs(input);
        assertThat(reconciler.reconcile(null, null)).isNull();
    }
}
