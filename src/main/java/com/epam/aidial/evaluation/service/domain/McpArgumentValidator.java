package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.TemplateVariableExtractor.ExtractedVariable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Cross-checks an MCP suite's {@code argumentTemplate.arguments} against the selected tool's
 * {@code toolRef.inputSchema}: every property listed in the schema's {@code required} array must be
 * satisfied by a non-blank effective value.
 *
 * <p>Arguments the schema does not declare are deliberately <b>not</b> flagged: JSON Schema allows
 * additional properties by default, and {@code toolRef.inputSchema} is a client-supplied snapshot
 * rather than a live read of the tool, so a stale snapshot must never make a working suite invalid.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class McpArgumentValidator {

    private final TemplateVariableExtractor templateVariableExtractor;
    private final JsonSchemaPropertyExtractor jsonSchemaPropertyExtractor;

    private static final String ARGUMENTS_PATH = "$.argumentTemplate.arguments";

    /**
     * @param inputSchema the tool's input JSON schema (may be null)
     * @param arguments   the suite's argument template arguments (may be null)
     * @param bindings    the suite's input bindings (may be null)
     * @return one warning per unsatisfied required argument (never a warning about an extra argument)
     */
    public List<ValidationWarningDto> validate(
            Map<String, Object> inputSchema, Map<String, Object> arguments, List<InputBindingDto> bindings) {
        // An absent, malformed, or property-less schema leaves declaredProperties empty, which skips
        // every required name below — a schema we cannot read must not invalidate the suite.
        final Set<String> declaredProperties = jsonSchemaPropertyExtractor.propertyNames(inputSchema);
        final Map<String, Object> effectiveArguments = arguments != null ? arguments : Map.of();
        final List<ValidationWarningDto> warnings = new ArrayList<>();

        for (String property : jsonSchemaPropertyExtractor.requiredNames(inputSchema)) {
            if (declaredProperties.contains(property) && !isSatisfied(effectiveArguments, property, bindings)) {
                warnings.add(requiredWarning(property));
            }
        }

        return warnings;
    }

    /**
     * Resolves an argument's <i>effective</i> value far enough to tell "the author left this empty"
     * apart from "the value arrives at run time". A placeholder with no binding is deliberately
     * treated as satisfied here: {@link BindingValidator} already reports the unbound variable, and
     * reporting it twice would show one mistake as two problems.
     */
    private boolean isSatisfied(Map<String, Object> arguments, String property, List<InputBindingDto> bindings) {
        if (!arguments.containsKey(property)) {
            return false;
        }
        final Object value = arguments.get(property);
        if (!(value instanceof String text)) {
            // A non-string constant (number, boolean, object, array) is always a value; null is not.
            return value != null;
        }

        final ExtractedVariable placeholder = templateVariableExtractor.parsePlaceholder(text);
        if (placeholder == null) {
            return !text.isBlank();
        }

        final InputBindingDto bindingDto = findBinding(bindings, placeholder.getName());
        if (bindingDto == null) {
            // No binding: BindingValidator reports the unbound variable unless the placeholder carries
            // a default, so only a default is ours to judge — and a blank one is not a value.
            return !placeholder.isHasDefault() || isNonBlank(placeholder.getDefaultValue());
        }
        if (bindingDto.getDataField() != null && !bindingDto.getDataField().isBlank()) {
            return true;
        }
        return isNonBlank(bindingDto.getConstantValue());
    }

    private static InputBindingDto findBinding(List<InputBindingDto> bindings, String templateVariable) {
        if (bindings == null) {
            return null;
        }
        return bindings.stream()
                .filter(b -> b != null && templateVariable.equals(b.getTemplateVariable()))
                .findFirst()
                .orElse(null);
    }

    private static boolean isNonBlank(Object value) {
        if (value == null) {
            return false;
        }
        return !(value instanceof String text) || !text.isBlank();
    }

    private static ValidationWarningDto requiredWarning(String property) {
        return ValidationWarningDto.builder()
                .fieldName(property)
                .path(ARGUMENTS_PATH)
                .message("Required tool argument '" + property + "' has no value")
                .code(ValidationWarningCode.REQUIRED)
                .build();
    }
}
