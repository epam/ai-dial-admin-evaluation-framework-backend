package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.model.TestCase;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.data.db.repository.TestCaseRepository;
import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.service.domain.dto.FormPartDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.KeyValueTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.MultipartFormDataRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedFormPartDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedJsonBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedMultipartBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.ResolvedUrlEncodedBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.UrlEncodedFormRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.exception.RequestBodyEvaluationException;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import com.epam.aidial.evaluation.service.domain.mapper.ValidationWarningsSerializer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves effective template with effective bindings and test case data.
 * Returns ResolvedRequestDto with resolved URL, queryParams, headers, body, and warnings.
 */
@Slf4j
@Service
@LogExecution
@RequiredArgsConstructor
public class ResolvedRequestService {

    private final TestSuiteRepository testSuiteRepository;
    private final TestCaseRepository testCaseRepository;
    private final JsonbMapper jsonbMapper;
    private final ValidationWarningsSerializer warningsSerializer;
    private final TemplateContentResolver templateContentResolver;
    private final RequestBodyEvaluator requestBodyEvaluator;

    @Transactional(value = "metaTransactionManager", readOnly = true)
    public ResolvedRequestDto resolveRequest(UUID testSuiteId, UUID testCaseId) {
        TestSuite suite = testSuiteRepository
                .findById(testSuiteId)
                .orElseThrow(() -> new EntityNotFoundException("TestSuite not found: " + testSuiteId));
        // Test cases now live on the dataset; resolve via the suite's datasetId so the suite-scoped
        // route still returns the test case the caller meant, while the underlying lookup is dataset-rooted.
        TestCase tc = testCaseRepository
                .findByIdAndDatasetId(testCaseId, suite.getDatasetId())
                .orElseThrow(() -> new EntityNotFoundException("TestCase not found: " + testCaseId));

        // Template + bindings are suite-owned now (overrides have been dropped per the dataset refactor).
        RequestTemplateDto template = jsonbMapper.mapRequestTemplate(suite.getRequestTemplate());
        List<InputBindingDto> bindings = jsonbMapper.mapInputBindings(suite.getInputBindings());

        Map<String, Object> data = warningsSerializer.deserializeMap(tc.getData());

        return resolve(template, bindings, data);
    }

    /**
     * Resolves template with bindings and data per the resolution flow in design.md. This is the
     * <b>preview</b> variant: a JSON body's JSONata evaluation runs with an empty request-template frame
     * (no turn history available in a suite/test-case preview), and a JSONata evaluation failure is
     * downgraded to a validation warning (never thrown) so preview/try-it-out never fails outright.
     */
    public ResolvedRequestDto resolve(
            RequestTemplateDto template, List<InputBindingDto> bindings, Map<String, Object> data) {
        return resolveInternal(template, bindings, data, Map.of(), true);
    }

    /**
     * Resolves template with bindings and data for the <b>run</b> path: identical url/query/headers/body
     * handling to {@link #resolve}, except a JSON body's JSONata evaluation runs with {@code frameBindings}
     * (the previous turn's reconciled extracted response columns, or empty for turn 0 / a single-turn case)
     * and lets {@link RequestBodyEvaluationException} propagate — the run-path fail-fast contract requires
     * the caller to turn an evaluation failure into an ERROR row, not a silently-downgraded warning.
     */
    public ResolvedRequestDto resolveForRun(
            RequestTemplateDto template,
            List<InputBindingDto> bindings,
            Map<String, Object> data,
            Map<String, Object> frameBindings) {
        return resolveInternal(template, bindings, data, frameBindings != null ? frameBindings : Map.of(), false);
    }

    private ResolvedRequestDto resolveInternal(
            RequestTemplateDto template,
            List<InputBindingDto> bindings,
            Map<String, Object> data,
            Map<String, Object> frameBindings,
            boolean preview) {
        List<ValidationWarningDto> warnings = new ArrayList<>();

        if (template == null) {
            warnings.add(ValidationWarningDto.builder()
                    .path("$")
                    .message("No request template configured")
                    .code(ValidationWarningCode.REQUIRED)
                    .build());
            return ResolvedRequestDto.builder().warnings(warnings).build();
        }

        Map<String, InputBindingDto> bindingByVar = (bindings != null ? bindings : List.<InputBindingDto>of())
                .stream()
                        .filter(b -> b != null && b.getTemplateVariable() != null)
                        .collect(Collectors.toMap(InputBindingDto::getTemplateVariable, b -> b, (a, b) -> a));

        Map<String, Object> safeData = data != null ? data : Map.of();

        // Resolve URL
        String resolvedUrl = template.getUrlTemplate() != null
                ? templateContentResolver.resolveString(template.getUrlTemplate(), bindingByVar, safeData, warnings)
                : null;

        // Resolve query params
        List<KeyValueTemplateDto> resolvedQueryParams = null;
        if (template.getQueryParams() != null) {
            resolvedQueryParams = new ArrayList<>();
            for (KeyValueTemplateDto kv : template.getQueryParams()) {
                if (kv != null) {
                    String val = kv.getValue() != null
                            ? templateContentResolver.resolveString(kv.getValue(), bindingByVar, safeData, warnings)
                            : null;
                    resolvedQueryParams.add(KeyValueTemplateDto.builder()
                            .key(kv.getKey())
                            .value(val)
                            .build());
                }
            }
        }

        // Resolve headers
        List<KeyValueTemplateDto> resolvedHeaders = null;
        if (template.getHeaders() != null) {
            resolvedHeaders = new ArrayList<>();
            for (KeyValueTemplateDto kv : template.getHeaders()) {
                if (kv != null) {
                    String val = kv.getValue() != null
                            ? templateContentResolver.resolveString(kv.getValue(), bindingByVar, safeData, warnings)
                            : null;
                    resolvedHeaders.add(KeyValueTemplateDto.builder()
                            .key(kv.getKey())
                            .value(val)
                            .build());
                }
            }
        }

        // Resolve body (content-type aware)
        ResolvedBodyDto resolvedBody =
                resolveBody(template.getBody(), bindingByVar, safeData, warnings, frameBindings, preview);

        return ResolvedRequestDto.builder()
                .url(resolvedUrl)
                .queryParams(resolvedQueryParams)
                .headers(resolvedHeaders)
                .body(resolvedBody)
                .warnings(warnings.isEmpty() ? List.of() : warnings)
                .build();
    }

    private ResolvedBodyDto resolveBody(
            RequestBodyDto body,
            Map<String, InputBindingDto> bindingByVar,
            Map<String, Object> data,
            List<ValidationWarningDto> warnings,
            Map<String, Object> frameBindings,
            boolean preview) {
        if (body == null) {
            return null;
        }
        if (body instanceof JsonRequestBodyDto jsonBody) {
            return resolveJsonBody(jsonBody, bindingByVar, data, warnings, frameBindings, preview);
        } else if (body instanceof MultipartFormDataRequestBodyDto multipartBody) {
            return resolveMultipartBody(multipartBody, bindingByVar, data, warnings);
        } else if (body instanceof UrlEncodedFormRequestBodyDto urlEncodedBody) {
            return resolveUrlEncodedBody(urlEncodedBody, bindingByVar, data, warnings);
        }
        return null;
    }

    /**
     * Resolves the JSON body template by evaluating it as JSONata against {@code frameBindings}. For a
     * preview resolution, a JSONata evaluation failure must never fail preview/try-it-out: it is downgraded
     * to a validation warning and the resolved body content is {@code null}. For a run resolution, the
     * failure propagates as {@link RequestBodyEvaluationException} — the caller (the turn loop) is
     * responsible for turning it into a fail-fast ERROR row.
     */
    private ResolvedJsonBodyDto resolveJsonBody(
            JsonRequestBodyDto body,
            Map<String, InputBindingDto> bindingByVar,
            Map<String, Object> data,
            List<ValidationWarningDto> warnings,
            Map<String, Object> frameBindings,
            boolean preview) {
        if (body.getContent() == null) {
            return ResolvedJsonBodyDto.builder().content(null).build();
        }
        if (!preview) {
            Map<String, Object> resolvedMap =
                    requestBodyEvaluator.evaluate(body.getContent(), bindingByVar, data, frameBindings, warnings);
            return ResolvedJsonBodyDto.builder().content(resolvedMap).build();
        }
        Map<String, Object> resolvedMap;
        try {
            resolvedMap =
                    requestBodyEvaluator.evaluate(body.getContent(), bindingByVar, data, frameBindings, warnings);
        } catch (RequestBodyEvaluationException e) {
            log.warn("Failed to evaluate JSON request body template for preview: {}", e.getMessage(), e);
            warnings.add(ValidationWarningDto.builder()
                    .path("$.requestTemplate.body")
                    .message("Failed to evaluate request body template: " + e.getMessage())
                    .code(ValidationWarningCode.TYPE)
                    .build());
            resolvedMap = null;
        }
        return ResolvedJsonBodyDto.builder().content(resolvedMap).build();
    }

    private ResolvedMultipartBodyDto resolveMultipartBody(
            MultipartFormDataRequestBodyDto body,
            Map<String, InputBindingDto> bindingByVar,
            Map<String, Object> data,
            List<ValidationWarningDto> warnings) {
        if (body.getContent() == null) {
            return ResolvedMultipartBodyDto.builder().parts(List.of()).build();
        }
        List<ResolvedFormPartDto> resolvedParts = new ArrayList<>();
        for (FormPartDto part : body.getContent()) {
            if (part == null) {
                continue;
            }
            Object resolvedValue = part.getValue() != null
                    ? templateContentResolver.resolveObject(part.getValue(), bindingByVar, data, warnings)
                    : null;
            String resolvedFilename = part.getFilename() != null
                    ? templateContentResolver.resolveString(part.getFilename(), bindingByVar, data, warnings)
                    : null;
            resolvedParts.add(ResolvedFormPartDto.builder()
                    .name(part.getName())
                    .type(part.getType())
                    .resolvedValue(resolvedValue)
                    .filename(resolvedFilename)
                    .build());
        }
        return ResolvedMultipartBodyDto.builder().parts(resolvedParts).build();
    }

    private ResolvedUrlEncodedBodyDto resolveUrlEncodedBody(
            UrlEncodedFormRequestBodyDto body,
            Map<String, InputBindingDto> bindingByVar,
            Map<String, Object> data,
            List<ValidationWarningDto> warnings) {
        if (body.getContent() == null) {
            return ResolvedUrlEncodedBodyDto.builder().entries(List.of()).build();
        }
        List<KeyValueTemplateDto> resolvedEntries = new ArrayList<>();
        for (KeyValueTemplateDto kv : body.getContent()) {
            if (kv == null) {
                continue;
            }
            String resolvedValue = kv.getValue() != null
                    ? templateContentResolver.resolveString(kv.getValue(), bindingByVar, data, warnings)
                    : null;
            resolvedEntries.add(KeyValueTemplateDto.builder()
                    .key(kv.getKey())
                    .value(resolvedValue)
                    .build());
        }
        return ResolvedUrlEncodedBodyDto.builder().entries(resolvedEntries).build();
    }
}
