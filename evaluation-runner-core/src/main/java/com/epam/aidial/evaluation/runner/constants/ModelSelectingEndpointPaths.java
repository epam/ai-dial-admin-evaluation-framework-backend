package com.epam.aidial.evaluation.runner.constants;

import java.util.Set;

/** Canonical DIAL Core paths for APIs that select the deployment from the request-body model. */
public final class ModelSelectingEndpointPaths {

    public static final String OPENAI_RESPONSES = "/openai/v1/responses";
    public static final String ANTHROPIC_MESSAGES = "/anthropic/v1/messages";
    public static final Set<String> ALL = Set.of(OPENAI_RESPONSES, ANTHROPIC_MESSAGES);

    private ModelSelectingEndpointPaths() {}
}
