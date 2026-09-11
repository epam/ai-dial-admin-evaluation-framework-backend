## Why

The fixed-path OpenAI Responses and Anthropic Messages APIs select their deployment from the JSON request body's `model` field, but the framework currently validates this only partially for Anthropic templates and does not enforce it after JSONata resolution. A mismatched model can therefore route execution differently from the suite's declared deployment, including when the CLI overrides the target deployment.

## What Changes

- Replace the Anthropic-only best-effort check with one shared validation rule for exact `/openai/v1/responses` and `/anthropic/v1/messages` requests.
- For plain JSON `content` templates, require `model` to be a literal string without any substring matching the `${{...}}` placeholder grammar and to equal the suite's `deploymentRef.id`; missing, null, non-string, dynamic, and mismatched values make the saved suite invalid through a dedicated `REQUEST_BODY_VALIDATION_ERROR` warning.
- Skip static model validation for `jsonataContent` while retaining its existing syntax validation.
- Validate the resolved body immediately before invocation in backend runs, CLI runs, and Try-It-Out, using the effective target deployment ID. A missing or non-JSON body, an invalid `model`, or a mismatch prevents the HTTP request and is reported as `REQUEST_BODY_VALIDATION_ERROR`.
- Apply both static and runtime rules independently to every request in a multi-request chain.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `test-suites`: Strengthen static suite validation for model-selecting fixed-path APIs.
- `request-template`: Define the shared resolved-body model validation and error contract.
- `eval-execution-engine`: Enforce pre-invocation validation and ERROR-row behavior in shared execution.
- `try-it-out`: Prevent invocation and expose the dedicated validation error for invalid resolved models.
- `eval-cli`: Validate against the effective CLI target deployment, including `--deployment-id` overrides.

## Impact

- Affected areas: suite soft validation in the root service and pre-invocation execution in `evaluation-runner-core`, consumed by backend runs and `eval-cli`; Try-It-Out integrates the same validator.
- Introduces a reusable runner-core request-model validator, a dedicated validation exception, and the persisted/returned `REQUEST_BODY_VALIDATION_ERROR` execution code.
- Existing suites using either fixed-path API may become invalid on revalidation when plain `content.model` is absent, non-literal, non-string, or mismatched. The backend's existing `isValid=false` run guard prevents newly invalid plain-content suites from reaching execution; runtime validation protects JSONata-authored suites, CLI deployment overrides, and previously persisted suites that still reach Phase 1.
- No REST DTO, database schema, dependency, security, or configuration changes are required. Existing JSONata syntax validation remains unchanged.
- Tests cover both canonical endpoints, all invalid model shapes, JSONata static deferral, multi-request paths, backend and CLI runtime execution, Try-It-Out, target overrides, and proof that validation failures never invoke DIAL Core.
