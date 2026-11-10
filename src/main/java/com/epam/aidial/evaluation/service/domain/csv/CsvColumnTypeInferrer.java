package com.epam.aidial.evaluation.service.domain.csv;

import com.epam.aidial.evaluation.runner.client.dialcore.SkillRefResolver;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Single owner of CSV schema-detection type inference: decides the {@link SchemaFieldType} of one
 * non-blank cell and folds per-cell types into a column type. Plain CSV carries no type metadata, so this
 * is how every non-string type survives a CSV export → import round trip.
 *
 * <p>Cell branch order: {@code OBJECT} (parses as a JSON object) → {@code ARRAY} (parses as a JSON
 * array) → {@code BOOLEAN} (literal {@code true}/{@code false}, case-insensitive; never {@code 1}/{@code
 * 0}) → {@code INTEGER} (whole number) → {@code NUMBER} (decimal) → {@code SKILL} (well-formed skill
 * reference per {@link SkillRefResolver#validate}, the same rule that drives the {@code SKILL} validation
 * warning, so an inferred {@code SKILL} column never warns) → {@code STRING}.
 *
 * <p>Column widening ({@link #widen}): equal types stay; {@code INTEGER} + {@code NUMBER} → {@code
 * NUMBER}; any other mix (including {@code SKILL} with any non-{@code SKILL} type) → {@code STRING}.
 * Callers skip blank cells, so a blank cell never affects a column's type. {@code FILE} is never inferred
 * here — it comes only from a ZIP manifest or the manifest-less {@code files/…} column hint.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class CsvColumnTypeInferrer {

    private static final Pattern INTEGER_PATTERN = Pattern.compile("^-?\\d+$");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("^-?\\d+\\.?\\d*$");
    private static final Set<SchemaFieldType> NUMERIC_PAIR = Set.of(SchemaFieldType.INTEGER, SchemaFieldType.NUMBER);

    private final ObjectMapper objectMapper;
    private final SkillRefResolver skillRefResolver;

    /**
     * Infers the type of a single non-blank, trimmed cell value.
     *
     * @param value trimmed, non-blank cell value
     * @return the inferred type; never {@code null} and never {@link SchemaFieldType#FILE}
     */
    public SchemaFieldType inferCellType(String value) {
        if (value.startsWith("{") && isJson(value)) {
            return SchemaFieldType.OBJECT;
        }
        if (value.startsWith("[") && isJson(value)) {
            return SchemaFieldType.ARRAY;
        }
        final String lower = value.toLowerCase();
        if ("true".equals(lower) || "false".equals(lower)) {
            return SchemaFieldType.BOOLEAN;
        }
        if (INTEGER_PATTERN.matcher(value).matches()) {
            return SchemaFieldType.INTEGER;
        }
        if (NUMBER_PATTERN.matcher(value).matches()) {
            return SchemaFieldType.NUMBER;
        }
        if (skillRefResolver.validate(value).isEmpty()) {
            return SchemaFieldType.SKILL;
        }
        return SchemaFieldType.STRING;
    }

    /**
     * Widens two column types to the broadest compatible type: equal → same; {@code INTEGER} + {@code
     * NUMBER} → {@code NUMBER}; everything else → {@code STRING}.
     */
    public static SchemaFieldType widen(SchemaFieldType a, SchemaFieldType b) {
        if (a == b) {
            return a;
        }
        if (Set.of(a, b).equals(NUMERIC_PAIR)) {
            return SchemaFieldType.NUMBER;
        }
        return SchemaFieldType.STRING;
    }

    private boolean isJson(String value) {
        try {
            objectMapper.readValue(value, Object.class);
            return true;
        } catch (JacksonException ignored) {
            // not valid JSON — falls through to the scalar branches
            return false;
        }
    }
}
