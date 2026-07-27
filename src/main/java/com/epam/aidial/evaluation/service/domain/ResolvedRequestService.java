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
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.UrlEncodedFormRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningCode;
import com.epam.aidial.evaluation.service.domain.dto.ValidationWarningDto;
import com.epam.aidial.evaluation.service.domain.exception.EntityNotFoundException;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import com.epam.aidial.evaluation.service.domain.mapper.ValidationWarningsSerializer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves effective template with effective bindings and test case data.
 * Returns ResolvedRequestDto with resolved URL, queryParams, headers, body, and warnings.
 */
@Service
@LogExecution
@RequiredArgsConstructor
public class ResolvedRequestService {

    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\$\\{\\{([^:|}]+)(?:\\|([^:}]+))?(?::([^}]*))?\\}\\}");

    /**
     * Matches a string that is exactly one placeholder with no surrounding text.
     * Uses [^}]+ which already covers type-hinted placeholders like ${{var|file}}.
     */
    private static final Pattern FULL_VALUE_PATTERN = Pattern.compile("^\\$\\{\\{[^}]+\\}\\}$");

    private final TestSuiteRepository testSuiteRepository;
    private final TestCaseRepository testCaseRepository;
    private final JsonbMapper jsonbMapper;
    private final ValidationWarningsSerializer warningsSerializer;
    private final TemplateVariableResolver templateVariableResolver;
    private final DialFileRefResolver dialFileRefResolver;

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
     * Resolves template with bindings and test case data per the resolution flow in design.md.
     * Single-request path: no chain values are available, so a {@code responseField} binding cannot resolve.
     */
    public ResolvedRequestDto resolve(
            RequestTemplateDto template, List<InputBindingDto> bindings, Map<String, Object> data) {
        return resolveInScope(template, bindings, ResolutionScope.ofData(data));
    }

    /**
     * Resolves a template against a full {@link ResolutionScope}. Used by the multi-request chain executor,
     * which supplies both the test case's data and the accumulating map of response columns extracted by
     * earlier chain requests, so {@code responseField} bindings resolve.
     */
    public ResolvedRequestDto resolveInScope(
            RequestTemplateDto template, List<InputBindingDto> bindings, ResolutionScope scope) {
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

        // Resolve URL
        String resolvedUrl = template.getUrlTemplate() != null
                ? resolveString(template.getUrlTemplate(), bindingByVar, scope, warnings)
                : null;

        // Resolve query params
        List<KeyValueTemplateDto> resolvedQueryParams = null;
        if (template.getQueryParams() != null) {
            resolvedQueryParams = new ArrayList<>();
            for (KeyValueTemplateDto kv : template.getQueryParams()) {
                if (kv != null) {
                    String val =
                            kv.getValue() != null ? resolveString(kv.getValue(), bindingByVar, scope, warnings) : null;
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
                    String val =
                            kv.getValue() != null ? resolveString(kv.getValue(), bindingByVar, scope, warnings) : null;
                    resolvedHeaders.add(KeyValueTemplateDto.builder()
                            .key(kv.getKey())
                            .value(val)
                            .build());
                }
            }
        }

        // Resolve body (content-type aware)
        ResolvedBodyDto resolvedBody = resolveBody(template.getBody(), bindingByVar, scope, warnings);

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
            ResolutionScope scope,
            List<ValidationWarningDto> warnings) {
        if (body == null) {
            return null;
        }
        if (body instanceof JsonRequestBodyDto jsonBody) {
            return resolveJsonBody(jsonBody, bindingByVar, scope, warnings);
        } else if (body instanceof MultipartFormDataRequestBodyDto multipartBody) {
            return resolveMultipartBody(multipartBody, bindingByVar, scope, warnings);
        } else if (body instanceof UrlEncodedFormRequestBodyDto urlEncodedBody) {
            return resolveUrlEncodedBody(urlEncodedBody, bindingByVar, scope, warnings);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private ResolvedJsonBodyDto resolveJsonBody(
            JsonRequestBodyDto body,
            Map<String, InputBindingDto> bindingByVar,
            ResolutionScope scope,
            List<ValidationWarningDto> warnings) {
        if (body.getContent() == null) {
            return ResolvedJsonBodyDto.builder().content(null).build();
        }
        Object resolved = resolveObject(body.getContent(), bindingByVar, scope, warnings);
        Map<String, Object> resolvedMap = resolved instanceof Map ? (Map<String, Object>) resolved : null;
        return ResolvedJsonBodyDto.builder().content(resolvedMap).build();
    }

    private ResolvedMultipartBodyDto resolveMultipartBody(
            MultipartFormDataRequestBodyDto body,
            Map<String, InputBindingDto> bindingByVar,
            ResolutionScope scope,
            List<ValidationWarningDto> warnings) {
        if (body.getContent() == null) {
            return ResolvedMultipartBodyDto.builder().parts(List.of()).build();
        }
        List<ResolvedFormPartDto> resolvedParts = new ArrayList<>();
        for (FormPartDto part : body.getContent()) {
            if (part == null) {
                continue;
            }
            Object resolvedValue =
                    part.getValue() != null ? resolveObject(part.getValue(), bindingByVar, scope, warnings) : null;
            String resolvedFilename = part.getFilename() != null
                    ? resolveString(part.getFilename(), bindingByVar, scope, warnings)
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
            ResolutionScope scope,
            List<ValidationWarningDto> warnings) {
        if (body.getContent() == null) {
            return ResolvedUrlEncodedBodyDto.builder().entries(List.of()).build();
        }
        List<KeyValueTemplateDto> resolvedEntries = new ArrayList<>();
        for (KeyValueTemplateDto kv : body.getContent()) {
            if (kv == null) {
                continue;
            }
            String resolvedValue =
                    kv.getValue() != null ? resolveString(kv.getValue(), bindingByVar, scope, warnings) : null;
            resolvedEntries.add(KeyValueTemplateDto.builder()
                    .key(kv.getKey())
                    .value(resolvedValue)
                    .build());
        }
        return ResolvedUrlEncodedBodyDto.builder().entries(resolvedEntries).build();
    }

    private String resolveString(
            String value,
            Map<String, InputBindingDto> bindingByVar,
            ResolutionScope scope,
            List<ValidationWarningDto> warnings) {
        StringBuffer sb = new StringBuffer();
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        while (matcher.find()) {
            String varName = matcher.group(1).trim();
            String defaultValue = matcher.group(3); // group 3: default value (group 2 is now type hint)
            InputBindingDto binding = bindingByVar.get(varName);
            Object resolved = templateVariableResolver.resolveVariable(varName, defaultValue, binding, scope, warnings);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(resolved != null ? resolved.toString() : ""));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private Object resolveObject(
            Object value,
            Map<String, InputBindingDto> bindingByVar,
            ResolutionScope scope,
            List<ValidationWarningDto> warnings) {
        if (value instanceof String str) {
            // Full-value replacement: if string is exactly one placeholder, return typed value
            if (FULL_VALUE_PATTERN.matcher(str).matches()) {
                Matcher m = PLACEHOLDER_PATTERN.matcher(str);
                if (m.find()) {
                    String varName = m.group(1).trim();
                    String typeHint = m.group(2); // group 2: type hint
                    String defaultValue = m.group(3); // group 3: default value
                    InputBindingDto binding = bindingByVar.get(varName);
                    Object resolved =
                            templateVariableResolver.resolveVariable(varName, defaultValue, binding, scope, warnings);
                    // Resolve FILE-typed placeholder to DIAL ref format for JSON/URL-encoded bodies
                    if (SchemaFieldType.FILE.name().equalsIgnoreCase(typeHint)
                            && resolved instanceof String resolvedRef) {
                        return dialFileRefResolver.resolveToDialRef(resolvedRef);
                    }
                    return resolved;
                }
            }
            // String interpolation
            return resolveString(str, bindingByVar, scope, warnings);
        } else if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(
                        String.valueOf(entry.getKey()), resolveObject(entry.getValue(), bindingByVar, scope, warnings));
            }
            return result;
        } else if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(resolveObject(item, bindingByVar, scope, warnings));
            }
            return result;
        }
        return value;
    }
}
