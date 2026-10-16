package com.epam.aidial.evaluation.runner.model.suite;

import com.epam.aidial.evaluation.runner.dto.ArgumentTemplateDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.model.target.McpToolTarget;
import java.util.List;

/**
 * A suite invoking one MCP tool with a single argument template (no request chain). Components are not
 * null-checked (trusted persisted data; request validation lives elsewhere).
 *
 * @param target the MCP deployment and tool invoked
 * @param argumentTemplate the tool's argument template
 * @param inputBindings input bindings applied to the argument template
 * @param responseColumns response column definitions
 */
public record McpSuite(
        McpToolTarget target,
        ArgumentTemplateDto argumentTemplate,
        List<InputBindingDto> inputBindings,
        List<ResponseColumnDefinitionDto> responseColumns)
        implements SuiteDefinition {}
