package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Decides whether a suite's effective input bindings reference at least one dataset schema field declared
 * {@code perTurn: true} — the sole signal that drives the unified turn loop's turn count (Decision 4 of the
 * {@code jsonata-request-templates} change): {@code N = multiTurnData.length} when {@code true}, else
 * {@code N = 1} regardless of how many turns the case's {@code multiTurnData} actually carries.
 */
@Component
@LogExecution
public class PerTurnBindingDetector {

    /**
     * Returns whether any binding's {@code dataField} matches a schema field with {@code perTurn = true}.
     * Null-safe: a null/empty {@code bindings} or {@code schema} answers {@code false}.
     *
     * @param bindings effective input bindings (suite-level or test-case override), may be null/empty
     * @param schema   the dataset's test-case schema field definitions, may be null/empty
     * @return {@code true} when at least one bound {@code dataField} is a {@code perTurn = true} field
     */
    public boolean referencesPerTurnField(List<InputBindingDto> bindings, List<FieldDefinitionDto> schema) {
        if (bindings == null || bindings.isEmpty() || schema == null || schema.isEmpty()) {
            return false;
        }

        Set<String> perTurnFieldNames = schema.stream()
                .filter(Objects::nonNull)
                .filter(field -> Boolean.TRUE.equals(field.getPerTurn()))
                .map(FieldDefinitionDto::getName)
                .collect(Collectors.toSet());
        if (perTurnFieldNames.isEmpty()) {
            return false;
        }

        return bindings.stream()
                .filter(Objects::nonNull)
                .map(InputBindingDto::getDataField)
                .filter(Objects::nonNull)
                .anyMatch(perTurnFieldNames::contains);
    }
}
