package com.epam.aidial.evaluation.cli.client.source;

import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** HTTP client for fetching a dataset's test-case schema from the source EF instance. */
@Slf4j
@Component
@LogExecution
@RequiredArgsConstructor
public class DatasetApiClient {

    @Qualifier("sourceRestClient")
    private final RestClient restClient;

    /**
     * Fetches the test-case schema of the given dataset via the source EF's
     * {@code GET /api/v1/datasets/{datasetId}} endpoint.
     *
     * <p>The response is bound to a minimal local record exposing only the {@code testCaseSchema}
     * field — {@code DatasetResponseDto} is a backend-only type unreachable from this module (see
     * {@code cli-multi-turn-multi-request-parity} design.md Decision 9). {@link FieldDefinitionDto}
     * is already a shared {@code runner.dto} type, so no schema type is duplicated; all other JSON
     * properties on the response (visibility, validation warnings, version, audit fields, ...) are
     * ignored.
     *
     * @param datasetId the dataset UUID whose test-case schema to fetch
     * @return the dataset's test-case schema field definitions, including each field's {@code
     *     perTurn} scope declaration; empty list when the dataset declares no schema
     * @throws org.springframework.web.client.RestClientException on HTTP errors
     */
    public List<FieldDefinitionDto> fetchTestCaseSchema(UUID datasetId) {
        final DatasetSchemaResponse response = restClient
                .get()
                .uri("/api/v1/datasets/{id}", datasetId)
                .retrieve()
                .body(DatasetSchemaResponse.class);

        final List<FieldDefinitionDto> schema =
                response != null && response.testCaseSchema() != null ? response.testCaseSchema() : List.of();
        log.debug("Fetched test-case schema ({} field(s)) for dataset {}", schema.size(), datasetId);
        return schema;
    }

    /**
     * Minimal local response envelope exposing only the field this client needs. Unknown JSON
     * properties on the actual {@code DatasetResponseDto} response are ignored by the module's
     * lenient {@code JsonMapperConfiguration} (see {@code cli-multi-turn-multi-request-parity}
     * design.md Decision 9).
     */
    record DatasetSchemaResponse(List<FieldDefinitionDto> testCaseSchema) {}
}
