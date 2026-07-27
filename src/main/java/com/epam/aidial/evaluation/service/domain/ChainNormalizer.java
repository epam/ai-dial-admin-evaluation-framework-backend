package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.service.domain.dto.ChainRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.ChainRequestType;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The single definition of "the chain". Turns a suite's asymmetric persisted shape — request 0 in the
 * flat {@code endpointRef}/{@code requestTemplate}/{@code inputBindings}/{@code responseColumns} columns
 * plus {@code requestLabel}, and requests {@code 1..N-1} in {@code additionalRequests} — into a uniform
 * ordered {@link RequestSpec} list.
 *
 * <p>The asymmetric persistence is deliberate: existing suites need no backfill, no snapshot version bump,
 * and no breaking API change. Normalizing at the service boundary means every consumer (per-element
 * validation, chain execution, {@code request_index}/{@code request_label} assignment, the chain-union
 * response-column set, CSV export column planning, query-schema discovery) is written once against the
 * symmetric list. The same normalization is applied to a live suite and to a frozen run snapshot, so the
 * two representations can never drift.
 *
 * <p>A single-request suite normalizes to a one-element chain, which is why the single-request path needs
 * no special case anywhere.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class ChainNormalizer {

    /** Prefix of the label assigned to a request that declares none; suffixed with the 1-based position. */
    private static final String DEFAULT_LABEL_PREFIX = "request-";

    private final JsonbMapper jsonbMapper;

    /** Normalizes a suite request DTO — the save-time validation path. */
    public List<RequestSpec> normalize(TestSuiteRequestDto dto) {
        if (dto == null) {
            return List.of();
        }
        return normalize(
                dto.getRequestLabel(),
                dto.getEndpointRef(),
                dto.getRequestTemplate(),
                dto.getInputBindings(),
                dto.getResponseColumns(),
                dto.getAdditionalRequests());
    }

    /** Normalizes a persisted suite entity — the run-creation guard and clone-validation paths. */
    public List<RequestSpec> normalize(TestSuite suite) {
        if (suite == null) {
            return List.of();
        }
        return normalize(
                suite.getRequestLabel(),
                jsonbMapper.mapEndpointContract(suite.getEndpointRef()),
                jsonbMapper.mapRequestTemplate(suite.getRequestTemplate()),
                jsonbMapper.mapInputBindings(suite.getInputBindings()),
                jsonbMapper.mapResponseColumns(suite.getResponseColumns()),
                jsonbMapper.mapAdditionalRequests(suite.getAdditionalRequests()));
    }

    /**
     * Normalizes a frozen run snapshot — the execution, export-planning, and schema-discovery paths.
     * A snapshot with no {@code additionalRequests} <i>is</i> a single-request chain, which is why adding
     * the field required no snapshot version bump and no version-conditional branch here.
     */
    public List<RequestSpec> normalize(SuiteSnapshotDto snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        return normalize(
                snapshot.getRequestLabel(),
                snapshot.getEndpointRef(),
                snapshot.getRequestTemplate(),
                snapshot.getInputBindings(),
                snapshot.getResponseColumns(),
                snapshot.getAdditionalRequests());
    }

    private List<RequestSpec> normalize(
            String requestLabel,
            EndpointContractDto endpointRef,
            RequestTemplateDto requestTemplate,
            List<InputBindingDto> inputBindings,
            List<ResponseColumnDefinitionDto> responseColumns,
            List<ChainRequestDto> additionalRequests) {

        final List<ChainRequestDto> extra =
                additionalRequests != null ? additionalRequests : List.<ChainRequestDto>of();
        final List<RequestSpec> chain = new ArrayList<>(extra.size() + 1);

        chain.add(new RequestSpec(
                0,
                resolveLabel(requestLabel, 0),
                ChainRequestType.HTTP,
                endpointRef,
                requestTemplate,
                inputBindings,
                responseColumns));

        for (int i = 0; i < extra.size(); i++) {
            final ChainRequestDto element = extra.get(i);
            final int index = i + 1;
            if (element == null) {
                // Defensive: a null array element cannot be authored through the API (Jackson would fail
                // the enclosing DTO first), but normalizing it to an empty HTTP request keeps every
                // downstream index assignment contiguous instead of throwing mid-chain.
                chain.add(new RequestSpec(
                        index, resolveLabel(null, index), ChainRequestType.HTTP, null, null, List.of(), List.of()));
                continue;
            }
            chain.add(new RequestSpec(
                    index,
                    resolveLabel(element.getLabel(), index),
                    element.getType(),
                    element.getEndpointRef(),
                    element.getRequestTemplate(),
                    element.getInputBindings(),
                    element.getResponseColumns()));
        }
        return List.copyOf(chain);
    }

    /**
     * Every normalized request carries exactly one non-null label: the declared one, or {@code request-{n}}
     * using the 1-based position. Defaulting here (rather than requiring labels) is what lets
     * {@code request_label} be written unconditionally onto result rows and keeps backward compatibility —
     * a suite saved before this capability existed resolves to {@code request-1}.
     */
    private static String resolveLabel(String declared, int index) {
        if (declared != null && !declared.isBlank()) {
            return declared.trim();
        }
        return DEFAULT_LABEL_PREFIX + (index + 1);
    }

    /**
     * The suite's <b>chain-union</b> response column set: request 0's columns followed by each subsequent
     * request's columns, in chain order. This is the effective response-column set of a multi-request
     * suite and the single source consumed by TSMD reference validation, CSV export column planning, and
     * query-schema discovery — none of which may use the flat {@code responseColumns} alone, since that
     * would silently omit every column owned by a later chain request.
     *
     * <p>Names are unique chain-wide (enforced at suite save), so the union needs no request qualification
     * and every column is owned by exactly one request. On the defensive chance of a duplicate reaching
     * here, the first declaration wins and the union stays free of repeats so downstream column plans do
     * not emit the same header twice.
     */
    public List<ResponseColumnDefinitionDto> chainResponseColumns(List<RequestSpec> chain) {
        if (chain == null || chain.isEmpty()) {
            return List.of();
        }
        final Map<String, ResponseColumnDefinitionDto> byName = new LinkedHashMap<>();
        for (RequestSpec request : chain) {
            for (ResponseColumnDefinitionDto column : request.safeResponseColumns()) {
                if (column != null && column.getName() != null) {
                    byName.putIfAbsent(column.getName(), column);
                }
            }
        }
        return List.copyOf(byName.values());
    }

    /** Chain-union response columns of a frozen snapshot. */
    public List<ResponseColumnDefinitionDto> chainResponseColumns(SuiteSnapshotDto snapshot) {
        return chainResponseColumns(normalize(snapshot));
    }

    /** Chain-union response columns of a suite request DTO. */
    public List<ResponseColumnDefinitionDto> chainResponseColumns(TestSuiteRequestDto dto) {
        return chainResponseColumns(normalize(dto));
    }

    /** Chain-union response columns of a persisted suite entity. */
    public List<ResponseColumnDefinitionDto> chainResponseColumns(TestSuite suite) {
        return chainResponseColumns(normalize(suite));
    }

    /**
     * The chain-union response columns serialized as a JSONB-shaped array, for consumers that take the raw
     * JSON text — notably TSMD reference validation. Using this instead of the suite's flat
     * {@code response_columns} column is what stops a metric bound to a later chain request's column from
     * being reported as an unresolved reference.
     */
    public String chainResponseColumnsJson(TestSuite suite) {
        return jsonbMapper.mapResponseColumns(chainResponseColumns(suite));
    }

    /** Chain-union response column <i>names</i> — the form reference-validation checks against. */
    public List<String> chainResponseColumnNames(List<RequestSpec> chain) {
        return chainResponseColumns(chain).stream()
                .map(ResponseColumnDefinitionDto::getName)
                .toList();
    }
}
