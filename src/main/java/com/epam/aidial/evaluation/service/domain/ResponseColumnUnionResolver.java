package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.data.db.model.TestSuite;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.SuiteSnapshotDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.mapper.JsonbMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for a suite's chain-wide response-column union — request #0's
 * {@code responseColumns} followed by every {@code additionalRequests[i].responseColumns}, in
 * chain order. Used by write-time validation (the {@code MAX_RESPONSE_COLUMNS} cap and the
 * {@code isResponseColumnsChanged} diff) and, in later change groups, by
 * {@code MetricDefinitionValidationService}, {@code EvalSummariesSchemaProvider} and
 * {@code EvalSummaryExportColumnPlanner} — every consumer that needs "all response columns this
 * suite can produce" resolves it here instead of re-deriving it.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class ResponseColumnUnionResolver {

    private final JsonbMapper jsonbMapper;

    /**
     * Union from an incoming/normalized request DTO — used at write time, before persistence.
     */
    public List<ResponseColumnDefinitionDto> unionFrom(TestSuiteRequestDto dto) {
        return union(dto.getResponseColumns(), dto.getAdditionalRequests());
    }

    /**
     * Union from a persisted entity — decodes both JSONB columns via {@link JsonbMapper}. Used to
     * derive the union that must be handed to {@code TestSuiteMetricDefinitionService.revalidateAllForSuite}
     * after a suite update.
     */
    public List<ResponseColumnDefinitionDto> unionFrom(TestSuite entity) {
        List<ResponseColumnDefinitionDto> suiteColumns = jsonbMapper.mapResponseColumns(entity.getResponseColumns());
        List<RequestDefinitionDto> additionalRequests =
                jsonbMapper.mapAdditionalRequests(entity.getAdditionalRequests());
        return union(suiteColumns, additionalRequests);
    }

    /**
     * Union from a run snapshot — request #0's {@code responseColumns} followed by each
     * {@code additionalRequests[i].responseColumns}, in chain order, exactly like the two overloads
     * above.
     */
    public List<ResponseColumnDefinitionDto> unionFrom(SuiteSnapshotDto snapshot) {
        return union(snapshot.getResponseColumns(), snapshot.getAdditionalRequests());
    }

    /**
     * Serialized-JSON variant of {@link #unionFrom(TestSuite)}, for callers that need the union as a
     * JSONB-ready string (e.g. {@code TestSuiteMetricDefinitionService.revalidateAllForSuite}).
     */
    public String unionJson(TestSuite entity) {
        return jsonbMapper.mapResponseColumns(unionFrom(entity));
    }

    private List<ResponseColumnDefinitionDto> union(
            List<ResponseColumnDefinitionDto> suiteColumns, List<RequestDefinitionDto> additionalRequests) {
        List<ResponseColumnDefinitionDto> union = new ArrayList<>();
        if (suiteColumns != null) {
            suiteColumns.stream().filter(Objects::nonNull).forEach(union::add);
        }
        if (additionalRequests != null) {
            for (RequestDefinitionDto request : additionalRequests) {
                if (request != null && request.getResponseColumns() != null) {
                    request.getResponseColumns().stream()
                            .filter(Objects::nonNull)
                            .forEach(union::add);
                }
            }
        }
        return union;
    }
}
