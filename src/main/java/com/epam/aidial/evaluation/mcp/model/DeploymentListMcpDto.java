package com.epam.aidial.evaluation.mcp.model;

import java.util.List;

/** Result shape of {@code list_deployments} (D-F8): the summaries plus their count. */
public record DeploymentListMcpDto(List<DeploymentSummaryMcpDto> deployments, int total) {}
