package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Single definition of the multi-turn <b>scope placement</b> rule — a declared per-turn field must live in
 * a {@code multiTurnData[i]} map, and a declared shared field must live in the {@code data} map — consumed
 * in both a throwing form ({@link #requireCorrectScope}, for write-path 400s) and a warning form
 * ({@link #inspect}, for recomputation passes that cannot throw). Scope membership itself comes from
 * {@link TestCaseFieldScopeResolver}; this class only detects and reports violations of it.
 *
 * <p>A key that matches no schema field has no scope to violate and is left untouched by this resolver —
 * it is reported elsewhere as an unknown-field warning.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class TestCaseDataScopeResolver {

    private final TestCaseFieldScopeResolver scopeResolver;

    /**
     * Inspects {@code data}/{@code turns} for scope violations against {@code schema} and returns the
     * buckets with the misplaced keys removed, alongside one {@link ValidationWarningDto} per occurrence.
     *
     * <p>Neither {@code data}, {@code turns}, nor any element of {@code turns} is mutated: {@link
     * ScopePlacement#shared()} and {@link ScopePlacement#turns()} are independent copies. A {@code null}
     * input map (top-level {@code data}, or the whole {@code turns} list) is preserved as {@code null} in
     * the corresponding output slot; a {@code null} element inside {@code turns} is preserved as {@code
     * null} at the same index. {@code turns} in the result always has the same size and order as the
     * input.
     *
     * <p>{@code data} is checked before {@code turns}, in index order, so warnings are returned data-side
     * first, then per turn in ascending index — this is what lets {@link #requireCorrectScope} reproduce
     * today's throwing order.
     */
    public ScopePlacement inspect(
            Map<String, Object> data, List<Map<String, Object>> turns, List<FieldDefinitionDto> schema) {
        final Set<String> perTurnNames = scopeResolver.perTurnFieldNames(schema);
        final Set<String> sharedNames = scopeResolver.sharedFieldNames(schema);

        final Set<String> misplacedFields = new LinkedHashSet<>();
        final List<ValidationWarningDto> warnings = new ArrayList<>();

        final Map<String, Object> shared = inspectShared(data, perTurnNames, misplacedFields, warnings);
        final List<Map<String, Object>> turnsResult = inspectTurns(turns, sharedNames, misplacedFields, warnings);

        return new ScopePlacement(shared, turnsResult, Set.copyOf(misplacedFields), List.copyOf(warnings));
    }

    /**
     * Delegates to {@link #inspect} and throws a {@link ValidationException} carrying the first warning's
     * message when any scope violation is found — the same exception type and, for a given violation, the
     * same text {@code MultiTurnFieldsValidator} throws today.
     */
    public void requireCorrectScope(
            Map<String, Object> data, List<Map<String, Object>> turns, List<FieldDefinitionDto> schema) {
        final ScopePlacement placement = inspect(data, turns, schema);
        if (!placement.warnings().isEmpty()) {
            throw new ValidationException(placement.warnings().get(0).getMessage());
        }
    }

    private static Map<String, Object> inspectShared(
            Map<String, Object> data,
            Set<String> perTurnNames,
            Set<String> misplacedFields,
            List<ValidationWarningDto> warnings) {
        if (data == null) {
            return null;
        }
        final Map<String, Object> shared = new LinkedHashMap<>(data);
        for (final String key : data.keySet()) {
            if (perTurnNames.contains(key)) {
                misplacedFields.add(key);
                shared.remove(key);
                warnings.add(warning(
                        key,
                        "$.data." + key,
                        "Field '" + key + "' is per-turn but currently specified on a test case level. Re-create "
                                + "column for correct data attachment",
                        null));
            }
        }
        return shared;
    }

    private static List<Map<String, Object>> inspectTurns(
            List<Map<String, Object>> turns,
            Set<String> sharedNames,
            Set<String> misplacedFields,
            List<ValidationWarningDto> warnings) {
        if (turns == null) {
            return null;
        }
        final List<Map<String, Object>> result = new ArrayList<>(turns.size());
        for (int i = 0; i < turns.size(); i++) {
            final Map<String, Object> turn = turns.get(i);
            if (turn == null) {
                result.add(null);
                continue;
            }
            final Map<String, Object> copy = new LinkedHashMap<>(turn);
            for (final String key : turn.keySet()) {
                if (sharedNames.contains(key)) {
                    misplacedFields.add(key);
                    copy.remove(key);
                    warnings.add(warning(
                            key,
                            "$.multiTurnData[" + i + "]." + key,
                            "Field '" + key + "' is shared (test-case-level) but values are specified on turn level. "
                                    + "Re-create column for correct data attachment",
                            i));
                }
            }
            result.add(copy);
        }
        return result;
    }

    private static ValidationWarningDto warning(String fieldName, String path, String message, Integer turnIndex) {
        return ValidationWarningDto.builder()
                .fieldName(fieldName)
                .path(path)
                .message(message)
                .code(ValidationWarningCode.INVALID_SCOPE)
                .turnIndex(turnIndex)
                .build();
    }

    /**
     * Result of {@link #inspect}: {@code shared} and {@code turns} are copies of the inputs with misplaced
     * keys removed, {@code misplacedFields} is the set of field names found in the wrong bucket (either
     * direction), and {@code warnings} carries one {@link ValidationWarningDto} per occurrence.
     */
    public record ScopePlacement(
            Map<String, Object> shared,
            List<Map<String, Object>> turns,
            Set<String> misplacedFields,
            List<ValidationWarningDto> warnings) {}
}
