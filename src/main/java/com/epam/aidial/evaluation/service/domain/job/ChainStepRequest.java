package com.epam.aidial.evaluation.service.domain.job;

import com.epam.aidial.evaluation.service.domain.RequestSpec;
import java.util.Map;

/**
 * Everything one chain step needs to execute, in one carrier so a new input can be added without changing
 * the {@link ChainStepExecutor} SPI signature.
 *
 * @param request       the normalized chain request to run — its own {@code endpointRef}, template,
 *                      bindings, and response columns
 * @param context       the run context (deployment ref, timeouts, retry policy, rate-limit gate, cancellation)
 * @param testCaseData  the test case's data map, for {@code dataField} bindings
 * @param responseValues the accumulating map of response column values extracted by <b>earlier</b> chain
 *                      requests of this test-case run, for {@code responseField} bindings. Read-only here;
 *                      the chain executor owns merging each step's output into it.
 */
public record ChainStepRequest(
        RequestSpec request,
        EvaluationContext context,
        Map<String, Object> testCaseData,
        Map<String, Object> responseValues) {}
