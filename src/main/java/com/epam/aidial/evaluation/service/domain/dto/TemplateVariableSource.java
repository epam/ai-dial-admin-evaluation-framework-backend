package com.epam.aidial.evaluation.service.domain.dto;

/**
 * Where in the request template a template variable appears.
 * HTTP suites use BODY, URL, QUERY, HEADER; MCP suites use ARGUMENT.
 */
public enum TemplateVariableSource {
    BODY,
    URL,
    QUERY,
    HEADER,
    ARGUMENT
}
