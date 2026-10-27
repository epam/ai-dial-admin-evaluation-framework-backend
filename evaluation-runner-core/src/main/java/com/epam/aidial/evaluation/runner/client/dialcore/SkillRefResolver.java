package com.epam.aidial.evaluation.runner.client.dialcore;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.util.BindingTypeResolver;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Owns the format of DIAL skill references ({@code skills/{bucket}/{path-to-skill}}): well-formedness
 * validation and canonicalisation for request payloads.
 *
 * <p>A skill is a DIAL directory resource that is only <em>referenced</em>; existence, access and sharing are
 * decided by DIAL Core. Deliberately independent of {@link DialFileRefResolver}: that resolver's prefix
 * whitelist and {@code files/} conversion are file semantics and must never touch a skill value.
 *
 * <p>Well-formed means, after trimming surrounding whitespace and at most one trailing {@code /}: the
 * case-sensitive prefix {@code skills/}, a non-empty bucket segment followed by at least one non-empty path
 * segment, and no {@code .}/{@code ..} segment, backslash or control character.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class SkillRefResolver {

    private static final String SKILLS_PREFIX = "skills/";
    private static final String SEGMENT_SEPARATOR = "/";
    private static final int MIN_SEGMENTS = 2;

    private final BindingTypeResolver bindingTypeResolver;

    /**
     * Returns whether a full-value placeholder with the given type hint and binding is a SKILL reference whose
     * resolved {@code String} value must be canonicalised with {@link #toCanonicalRef(String)}.
     *
     * <p>Same rule shape as {@code DialFileRefResolver#isFileReference}: an explicit type hint decides
     * ({@code skill}, case-insensitive, is SKILL; any other hint is not); with no hint the placeholder is SKILL
     * iff its binding's {@code dataField} names a schema field of type {@link SchemaFieldType#SKILL}.
     *
     * @param typeHint raw type hint from the placeholder ({@code null} when absent)
     * @param binding  the placeholder variable's binding, nullable
     * @param schema   test case schema, nullable
     */
    public boolean isSkillReference(String typeHint, InputBindingDto binding, List<FieldDefinitionDto> schema) {
        return bindingTypeResolver.isOfType(SchemaFieldType.SKILL, typeHint, binding, schema);
    }

    /**
     * Checks that a value is a well-formed skill reference.
     *
     * @return an empty optional when well-formed, otherwise a human-readable error
     */
    public Optional<String> validate(String value) {
        if (value == null) {
            return Optional.of("skill reference must not be null");
        }
        final String normalized = normalize(value);
        if (!normalized.startsWith(SKILLS_PREFIX)) {
            return Optional.of("skill reference must start with '" + SKILLS_PREFIX + "': " + value);
        }
        if (containsForbiddenCharacter(normalized)) {
            return Optional.of("skill reference must not contain a backslash or control character: " + value);
        }
        final String[] segments = normalized.substring(SKILLS_PREFIX.length()).split(SEGMENT_SEPARATOR, -1);
        if (segments.length < MIN_SEGMENTS) {
            return Optional.of("skill reference must be 'skills/{bucket}/{path}': " + value);
        }
        for (final String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return Optional.of("skill reference must not contain empty, '.' or '..' segments: " + value);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the canonical reference of a well-formed value; a malformed value is returned unchanged
     * (DIAL Core rejects it at request time).
     */
    public String toCanonicalRef(String value) {
        if (value == null || validate(value).isPresent()) {
            return value;
        }
        return normalize(value);
    }

    private String normalize(String value) {
        final String trimmed = value.strip();
        return trimmed.endsWith(SEGMENT_SEPARATOR) ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private boolean containsForbiddenCharacter(String value) {
        return value.chars().anyMatch(c -> c == '\\' || Character.isISOControl(c));
    }
}
