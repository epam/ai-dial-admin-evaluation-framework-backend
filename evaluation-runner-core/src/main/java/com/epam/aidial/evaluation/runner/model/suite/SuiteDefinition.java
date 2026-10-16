package com.epam.aidial.evaluation.runner.model.suite;

import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.model.target.SuiteTarget;
import java.util.List;

/**
 * Internal, derived view of everything a test suite does: WHERE it calls ({@link SuiteTarget}) paired with
 * WHAT it sends (the invocation shape that target kind understands). Never serialized: wire DTOs, DB columns
 * and persisted snapshot JSON keep the flat fields, and this type is derived from them at the boundaries
 * (see {@link SuiteDefinitions}).
 *
 * <p>Pairing target and invocation shape makes illegal combinations (for example an MCP target carrying a
 * request chain) unrepresentable. Implementations do not null-check their components: snapshots and
 * entities are trusted persisted data, and request-boundary validation lives elsewhere.
 */
public sealed interface SuiteDefinition permits HttpSuite, McpSuite {

    /** Where this suite calls. */
    SuiteTarget target();

    /**
     * Response columns of request #0 (HTTP) or of the tool call (MCP) - what the engine's
     * {@code responseColumns} parameter used to receive.
     */
    List<ResponseColumnDefinitionDto> responseColumns();
}
