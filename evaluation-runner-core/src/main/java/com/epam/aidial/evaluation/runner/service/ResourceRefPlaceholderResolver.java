package com.epam.aidial.evaluation.runner.service;

import com.epam.aidial.evaluation.runner.client.dialcore.DialFileRefResolver;
import com.epam.aidial.evaluation.runner.client.dialcore.SkillRefResolver;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Single seam that converts the resolved value of a full-value {@code ${{...}}} placeholder when the
 * placeholder is a DIAL resource reference: a FILE reference becomes a DIAL ref ({@code files/<bucket>/...}),
 * a SKILL reference becomes its canonical form ({@code skills/<bucket>/<path>}).
 *
 * <p>Shared by {@link TemplateContentResolver}, {@link JsonataSourcePreprocessor} and
 * {@link McpRequestResolver} so the paths cannot drift. Each reference type owns its own decision
 * ({@link DialFileRefResolver#isFileReference}, {@link SkillRefResolver#isSkillReference}); a value that is not
 * a {@code String}, or a placeholder that is neither FILE nor SKILL, is returned unchanged. A SKILL value is
 * never converted by {@link DialFileRefResolver#resolveToDialRef}.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class ResourceRefPlaceholderResolver {

    private final DialFileRefResolver dialFileRefResolver;
    private final SkillRefResolver skillRefResolver;

    /**
     * Converts {@code resolved} when it is a {@code String} and the placeholder is a FILE or SKILL reference.
     *
     * @param resolved the placeholder's resolved value
     * @param typeHint raw type hint from the placeholder ({@code null} when absent)
     * @param binding  the placeholder variable's binding, nullable
     * @param schema   test case schema, nullable
     */
    public Object resolve(Object resolved, String typeHint, InputBindingDto binding, List<FieldDefinitionDto> schema) {
        if (!(resolved instanceof String ref)) {
            return resolved;
        }
        if (dialFileRefResolver.isFileReference(typeHint, binding, schema)) {
            return dialFileRefResolver.resolveToDialRef(ref);
        }
        if (skillRefResolver.isSkillReference(typeHint, binding, schema)) {
            return skillRefResolver.toCanonicalRef(ref);
        }
        return resolved;
    }
}
