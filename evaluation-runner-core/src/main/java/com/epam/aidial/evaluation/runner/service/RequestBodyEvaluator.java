package com.epam.aidial.evaluation.runner.service;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.runner.exception.RequestBodyEvaluationException;
import com.epam.aidial.evaluation.runner.exception.ValidationException;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Evaluates a request-template JSON body — the legacy structural {@code content} ({@code Map}) or the
 * JSONata source {@code jsonataContent} ({@code String}) — as JSONata, converging both authoring styles
 * on the same evaluation path.
 *
 * <ul>
 *   <li>{@code content}: resolved via {@link TemplateContentResolver#resolveObject} (existing
 *       {@code ${{}}} structural substitution), then serialized to a JSON string (preserving explicit
 *       nulls) — that string is valid JSONata source because JSON is a syntactic subset of JSONata, so
 *       a plain object echoes itself.</li>
 *   <li>{@code jsonataContent}: preprocessed via {@link JsonataSourcePreprocessor#preprocess} (textual
 *       {@code ${{}}} substitution inside the JSONata source), then evaluated directly.</li>
 * </ul>
 *
 * <p>The two fields are mutually exclusive by write-time validation; when both are present here
 * (defensive-only — the write path guarantees this cannot happen), {@code jsonataContent} takes
 * precedence.
 *
 * <p>The evaluated result must be a JSON object (matching the existing "the body is an object"
 * contract) — any other result, or a JSONata parse/evaluation failure, is reported as a
 * {@link RequestBodyEvaluationException} for the caller to translate into an ERROR row (run-time) or a
 * validation warning (preview).
 */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class RequestBodyEvaluator {

    private final TemplateContentResolver templateContentResolver;
    private final JsonataSourcePreprocessor jsonataSourcePreprocessor;
    private final JsonataEvaluationService jsonataEvaluationService;
    private final ObjectMapper objectMapper;

    /**
     * Evaluates the request body template content against the given bindings/data/frame.
     *
     * @param content       {@link JsonRequestBodyDto#getContent()} — the legacy structural
     *                      {@code Map<String, Object>} template, or {@code null}
     * @param jsonataContent {@link JsonRequestBodyDto#getJsonataContent()} — the JSONata source
     *                      {@code String} template, or {@code null}
     * @param bindingByVar  input bindings keyed by template variable name
     * @param data          test case data used to resolve data-field bindings
     * @param frameBindings named variables bound into the JSONata evaluation frame (e.g. the
     *                      previous turn's extracted response columns); empty for a preview with no
     *                      turn history
     * @param warnings      accumulator for placeholder-resolution warnings
     * @return the evaluated request body as a JSON object, or {@code null} when both {@code content} and
     *     {@code jsonataContent} are {@code null} (no body)
     * @throws RequestBodyEvaluationException if the JSONata source fails to parse or evaluate, or the
     *     evaluation result is not a JSON object
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> evaluate(
            Map<String, Object> content,
            String jsonataContent,
            Map<String, InputBindingDto> bindingByVar,
            Map<String, Object> data,
            Map<String, Object> frameBindings,
            List<ValidationWarningDto> warnings) {
        if (content == null && jsonataContent == null) {
            return null;
        }

        String source;
        if (jsonataContent != null) {
            source = jsonataSourcePreprocessor.preprocess(jsonataContent, bindingByVar, data, warnings);
        } else {
            Object resolved = templateContentResolver.resolveObject(content, bindingByVar, data, warnings);
            source = TemplateContentResolver.serializeJsonPreservingNulls(objectMapper, resolved);
        }

        Map<String, Object> safeFrameBindings = frameBindings != null ? frameBindings : Map.of();
        Object result;
        try {
            result = jsonataEvaluationService.evaluate(source, null, safeFrameBindings);
        } catch (ValidationException | IllegalStateException e) {
            log.warn("Failed to evaluate request body JSONata source: {}", e.getMessage(), e);
            throw new RequestBodyEvaluationException("Failed to evaluate request body: " + e.getMessage(), e);
        }

        if (!(result instanceof Map)) {
            throw new RequestBodyEvaluationException("Evaluated request template must evaluate to a JSON object, got: "
                    + (result == null ? "null" : result.getClass().getSimpleName()));
        }
        return (Map<String, Object>) result;
    }
}
