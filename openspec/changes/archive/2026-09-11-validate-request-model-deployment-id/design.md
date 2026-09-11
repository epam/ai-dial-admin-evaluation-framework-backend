## Context

`SuiteValidationService` currently contains an Anthropic-only literal mismatch check. It skips missing, non-string, placeholder, and JSONata values, and OpenAI Responses has no equivalent. Runtime resolution is shared by the root application and `eval-cli` through `evaluation-runner-core`, but neither the turn executor nor Try-It-Out verifies that a successfully resolved body's model matches the effective deployment. Fixed-path calls do not carry the deployment ID in their URL, so this body field is the routing authority.

The solution must preserve soft suite validation, existing JSONata syntax validation, runner-core's DB-free boundary, per-request chain behavior, and CLI target overrides. No persistence, transaction, REST DTO, configuration, or security changes are needed.

## Goals / Non-Goals

**Goals:**

- Define one exact-path, model-shape, and equality rule shared by static and runtime validation.
- Reject invalid resolved bodies before HTTP invocation in backend runs, CLI, and Try-It-Out.
- Keep failures diagnosable through a dedicated error code and resolved request body.
- Cover every request/turn while retaining existing fail-fast semantics.

**Non-Goals:**

- Validate model fields for chat completions, embeddings, route passthrough, or subresource URLs.
- Evaluate `jsonataContent` or resolve `${{...}}` bindings during suite persistence.
- Add deployment ID as a JSONata frame binding or rewrite the author's body automatically.
- Change the non-executing resolved-request preview contract.

## Decisions

### 1. Centralize canonical paths and model validation in runner-core

Introduce runner-core constants for `/openai/v1/responses` and `/anthropic/v1/messages`, and make `DialCoreUrlBuilder` consume the same canonical values. Matching semantics remain consumer-specific: model validation uses exact, case-sensitive equality, while URL routing preserves its current behavior (`/openai/v1/responses` is a case-insensitive prefix and Anthropic Messages is exact). Add an injectable `RequestModelValidator` in the runner service layer with side-effect-free validation methods for authored JSON content and resolved bodies. It returns no error for non-target paths; for a target path it reports a missing/non-JSON body and missing/null/non-string/placeholder-containing/mismatched `model` values with a stable diagnostic. Placeholder detection reuses the runner's existing `${{...}}` grammar with `find()` semantics so an embedded placeholder is rejected rather than duplicating a regex or checking only a whole-string match.

The validator also exposes an execution-oriented method that throws a dedicated `RequestBodyValidationException` for the same error. This keeps static warning creation in the root service while ensuring all runtime consumers share identical endpoint and value semantics.

### 2. Static validation remains soft and inspects only plain content

Replace the current Anthropic block in `SuiteValidationService.validateRequest` with the shared validator. Call it only when the request has `JsonRequestBodyDto.content`; `jsonataContent` is deliberately deferred, although its existing neutralized syntax validation remains unchanged. Other authored body types remain outside this static model rule and retain their existing content-type validation. Convert an error into a `REQUEST_BODY_VALIDATION_ERROR` warning at the root or indexed body path, which marks the suite invalid but permits persistence. DTO- and entity-based validation already traverse the whole request chain, so both create/update and later revalidation receive the rule.

The backend's existing `createRun` guard rejects any `isValid=false` suite. Consequently, a newly saved plain-content mismatch cannot reach Phase 1; backend runtime coverage uses a JSONata body that resolves to an invalid model (or a deliberately pre-existing valid fixture), while runtime enforcement also protects CLI deployment overrides and persisted suites not yet revalidated.

### 3. Validate runtime content at the final pre-invocation boundary

In `TurnLoopExecutor`, validate after `RequestResolver.resolveForRun` has produced the resolved body and before URL construction/serialization/invocation. A target path requires `ResolvedJsonBodyDto`; an absent or different resolved-body type fails validation. Use `EvaluationContext.snapshotDeploymentRef.id`, which is the frozen backend deployment or CLI-selected target. Catch `RequestBodyValidationException` separately, serialize the already-resolved body into `requestBody`, and build an abort-before-request outcome using new `ExecutionErrorCodes.REQUEST_BODY_VALIDATION_ERROR`. Existing turn/request fail-fast behavior then handles chain termination without special orchestration logic.

Try-It-Out invokes the same validator immediately before DIAL Core. Single-invocation flows convert the exception into the established HTTP 400 `TryItOutValidationException`, retaining the resolved request and adding a `REQUEST_BODY_VALIDATION_ERROR` validation warning. Chained flows add an explicit `RequestBodyValidationException` catch alongside `RequestBodyEvaluationException`, convert it into the established status-code-zero failed-invocation envelope, retain the resolved request in the response/history, and stop the chain. The validator is not placed inside `validateResolutionResult`, because that would make a chained failure escape as HTTP 400 instead of the required in-band status-zero result. Add the same value to `ValidationWarningCode` for the single-invocation diagnostic; the preview endpoint itself does not run this check.

### 4. Keep error categories distinct

Add `REQUEST_BODY_VALIDATION_ERROR` to execution error constants and validation warning codes. Static and runtime model failures use the new code. JSONata parse/evaluation/non-object failures remain `REQUEST_BODY_EVALUATION_ERROR`; unrelated URL, serializer, or resolution failures remain `REQUEST_RESOLUTION_ERROR`.

### 5. Compatibility and documentation

No migration or rollout toggle is required. Existing plain-content suites targeting either canonical path can transition to invalid on create/update or explicit revalidation, while existing persisted valid suites are still protected at execution time. Because `createRun` guard #3 rejects any `isValid=false` suite, a plain-`content` suite that fails the new static check can never start a run at all; the runtime check therefore covers exactly three populations — `jsonataContent`-authored bodies, CLI `--deployment-id` target overrides, and suites persisted as valid before this rule existed. Update the Anthropic pattern documentation to describe the unified rule and add the OpenAI Responses path, and update its rows in `docs/patterns/README.md` and `AGENTS.md`. This is a feature-specific pattern change, so `openspec/config.yaml` and the OpenSpec spec index do not change.

## Risks / Trade-offs

- Previously accepted Anthropic bodies with missing/dynamic models and all equivalent OpenAI Responses bodies become invalid when statically revalidated. This is intentional because plain content is required to be deterministic.
- A CLI deployment override can make a source template fail until its resolved model targets the override. Runtime enforcement is necessary because source-side static validation cannot know the future target.
- Single and chained Try-It-Out retain their pre-existing outer failure shapes (HTTP 400 versus an in-band failed invocation), but both expose the same dedicated inner code and skip DIAL Core.
- Exact model-validation matching deliberately excludes case variants, query-in-path variants, and subresources; query parameters remain separately modeled and do not affect matching. This does not alter `DialCoreUrlBuilder`'s broader, case-insensitive Responses routing behavior.
