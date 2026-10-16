package com.epam.aidial.evaluation.runner.model.target;

import com.epam.aidial.evaluation.runner.dto.McpDeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.ToolReferenceDto;
import com.epam.aidial.evaluation.runner.model.SuiteType;

/**
 * MCP tool target. Components are not null-checked (trusted persisted data; request validation lives
 * elsewhere).
 */
public record McpToolTarget(McpDeploymentReferenceDto ref, ToolReferenceDto tool) implements SuiteTarget {

    @Override
    public SuiteType type() {
        return SuiteType.MCP_TOOL;
    }
}
