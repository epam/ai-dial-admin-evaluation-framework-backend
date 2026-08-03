# Design: Split JSONata body source out of `content` into `jsonataContent`

## Context

The archived `2026-07-31-jsonata-request-templates` change made `JsonRequestBodyDto.content` an `Object` holding either a `Map<String, Object>` (legacy structural template) or a `String` (JSONata source). Current state:

- `evaluation-runner-core/.../runner/dto/JsonRequestBodyDto.java` — one field, `private Object content`, annotated `@Schema(oneOf = {Map.class, String.class})`.
- `evaluation-runner-core/.../runner/service/RequestBodyEvaluator.java` — `evaluate(Object templateContent, …)` dispatches with `instanceof Map` / `instanceof String stringContent`, plus a third `else` branch throwing `RequestBodyEvaluationException("Unsupported request body template content type: …")`.
- `evaluation-runner-core/.../runner/service/RequestResolver.java` — `resolveJsonBody(...)` null-checks `body.getContent()` and passes it to the evaluator on both the run path (throws) and the preview path (downgrades to a `REQUEST_BODY_EVALUATION_ERROR` warning).
- `src/main/java/.../service/domain/TestSuiteRequestValidator.java` — `validateRequestTemplateBody` does `content == null || content instanceof Map → return`, `content instanceof String → neutralize + validateExpression` (400 on failure), and a trailing `throw new ValidationException("requestTemplate.body.content: must be a JSON object or a JSONata source string")` for anything else.
- `src/main/java/.../service/domain/TemplateVariableExtractor.java` — `extractFromBody` calls `extractFromObject(jsonBody.getContent(), BODY, …)`; `extractFromObject` recurses over `String`/`Map`/`List`, so a `String` content is scanned for `${{}}` only because it happens to hit the `instanceof String` arm of that generic walker.

Constraints:

- The feature is **not merged to `development`**; there is no released consumer and FE integration has not started, so a breaking wire-format change is acceptable exactly once, now.
- `test_suites.request_template` is opaque JSONB round-tripped through Jackson as a whole DTO — the union lives only in Java + JSON, not in any typed column, so no Flyway migration and no jOOQ regeneration are involved.
- The `evaluation-runner-core` module must stay DB-free and must not depend back on the EF backend; the DTO and evaluator live there, the write-time validator and variable extractor live in the EF backend.
- The field name is fixed by the stakeholder: exactly `jsonataContent`.

## Goals / Non-Goals

**Goals:**

- Make the `application/json` body contract statically typed: `content` is `Map<String, Object>`, `jsonataContent` is `String`.
- Keep evaluation semantics byte-for-byte identical — `${{}}` preprocessing, JSONata evaluation, must-evaluate-to-object contract, write-time neutralize-and-validate → 400, `REQUEST_BODY_EVALUATION_ERROR` warning on the preview path.
- Reject `content` + `jsonataContent` both non-null with HTTP 400 at suite create/update.
- Preserve `${{}}` variable extraction for JSONata bodies via an **explicit** scan of the new field.
- Produce an OpenAPI contract where both fields carry a concrete type instead of an untyped `oneOf`.

**Non-Goals:**

- No change to `JsonataSourcePreprocessor`, `TemplateContentResolver`, `JsonataEvaluationService`, frame bindings / `$history`, `TurnLoopExecutor`, streaming, or response-column extraction.
- No change to `multipart/form-data` or `application/x-www-form-urlencoded` bodies.
- No DB migration, no configuration property, no new capability, no new package.
- No backward compatibility shim accepting a `String` in `content` (deliberately breaking — see Decisions).

## Decisions

### D1: Two sibling fields on `JsonRequestBodyDto`, not a discriminated wrapper

`JsonRequestBodyDto` gets `private Map<String, Object> content` and `private String jsonataContent`, mutually exclusive by validation rather than by type.

- *Alternative — a nested `bodySource` wrapper object with its own `type` discriminator* (`{"type":"jsonata","source":"…"}`): expresses exclusivity structurally, but adds a nesting level to every legacy body, changes the Map-authored wire shape too (not just the JSONata one), and adds a second polymorphic hierarchy under an already-polymorphic `RequestBodyDto`. Rejected: cost lands on the common case to police a rare one.
- *Alternative — a fourth `contentType` subtype (e.g. `application/json+jsonata`)*: exclusivity becomes free (different classes), but `contentType` is also matched against `endpointRef.requestBodySchema.contentType` for the content-type-mismatch check, so a synthetic media type would leak into endpoint-conformance validation. Rejected.
- Chosen shape keeps the legacy body untouched, adds one nullable field, and pays only a single explicit both-set check.

### D2: Mutual exclusivity enforced in `TestSuiteRequestValidator`, surfaced as the existing `VALIDATION_ERROR` 400

The both-set check goes into `validateRequestTemplateBody` (already the owner of JSON-body write-time validation) as a `ValidationException` — mapped by `DefaultExceptionHandler` to HTTP 400 with `code: VALIDATION_ERROR`, the same envelope as the existing invalid-JSONata-source rejection. Message path stays field-qualified, e.g. `requestTemplate.body: content and jsonataContent are mutually exclusive`.

- *Alternative — Bean Validation (a class-level `@AssertTrue` on the DTO)*: the DTO lives in `evaluation-runner-core`, which is intentionally free of the EF backend's validation wiring, and per AGENTS.md programmatically-built execution-path objects are checked inline rather than via `Validator`. Rejected; keep the check with the other request-template write-time checks.
- No new `ErrorCode` — this is an ordinary request-shape violation.

### D3: `RequestBodyEvaluator` takes the DTO's two typed fields; the "unsupported content type" branch is deleted

Signature becomes field-presence dispatch instead of `instanceof` sniffing — `evaluate(Map<String, Object> content, String jsonataContent, bindingByVar, data, frameBindings, warnings)`:

- both null → `null` (no body), unchanged from today's `templateContent == null`;
- `jsonataContent != null` → `jsonataSourcePreprocessor.preprocess(...)`;
- otherwise → `templateContentResolver.resolveObject(...)` + `serializeJsonPreservingNulls(...)`.

Everything downstream (evaluate, object-contract check, `RequestBodyEvaluationException`) is untouched. The third `else` throw disappears because the compiler now guarantees the carrier types, and the `@SuppressWarnings("unchecked")` on the `content` cast is no longer needed for the input side.

- *Alternative — pass the whole `JsonRequestBodyDto`*: fewer parameters, but couples the evaluator to the DTO shape and makes it harder to reuse for a future non-DTO caller. Rejected in favor of the two typed parameters; `RequestResolver.resolveJsonBody` already holds the DTO and can unpack it.
- Precedence when both are set is defined (`jsonataContent` wins) purely as a defensive runtime fallback; the write path guarantees it never happens, so it is not a spec'd behavior.

### D4: `TemplateVariableExtractor` scans `jsonataContent` explicitly

`extractFromBody`'s `JsonRequestBodyDto` arm calls `extractFromObject(content, …)` **and** `extractFromString(jsonataContent, …)` when each is non-null. Today the String rides `extractFromObject`'s `instanceof String` arm for free; after the split, the `Map`-typed `content` can never reach that arm, so an omitted second call would silently stop extracting `${{}}` variables from JSONata bodies. That produces no compile error and no exception — only wrong binding validation (spurious orphan-binding / unbound-variable results and an empty `GET .../template-variables` for JSONata suites). This is the single highest-risk line in the change; it is called out again under Risks and gets dedicated unit + functional coverage.

### D5: Hard break — a `String` in `content` is rejected, not coerced

After the change, `content` is `Map<String, Object>`; Jackson fails deserialization of a String value there. No lenient-coercion `@JsonDeserialize` or "if it's a String, treat it as `jsonataContent`" migration path is added, because there is no released consumer to protect and a shim would have to live forever to be useful.

Consequence for dev environments on this branch: suite rows persisted with a String `content` in `request_template` JSONB fail to deserialize on read. Handling is operational, not code — see Migration Plan.

### D6: OpenAPI — two typed `@Schema` annotations replace `oneOf`

`content` gets a Map-shaped `@Schema` with a legacy structural example; `jsonataContent` gets `type = "string"` with the JSONata-source example currently carried on the union. The three `*-jsonata-body.json` example files move the JSONata source string from `"content"` to `"jsonataContent"`. This is the whole point of the change: `oneOf = {Map, String}` on one property renders as untyped `object`/`any` in Swagger UI and generated clients.

### Component interaction flow (after the change)

```
POST/PUT /api/v1/test-suites
  → TestSuiteRequestValidator.validateRequestTemplateBody
        both non-null                 → ValidationException → 400 VALIDATION_ERROR
        jsonataContent non-null       → preprocessor.neutralize → validateExpression → 400 on syntax error
        content non-null (Map) / both null → accept
  → TemplateVariableExtractor.extractFromBody
        content        → extractFromObject(Map)
        jsonataContent → extractFromString(String)   ← explicit, new
  → persisted as opaque request_template JSONB (whole-DTO Jackson round-trip)

run / preview
  → RequestResolver.resolveJsonBody(JsonRequestBodyDto …)
        both null → ResolvedJsonBodyDto(content = null)
        else      → RequestBodyEvaluator.evaluate(content, jsonataContent, …)
                        jsonataContent → JsonataSourcePreprocessor.preprocess
                        content        → TemplateContentResolver.resolveObject + serializeJsonPreservingNulls
                        → JsonataEvaluationService.evaluate → must be a JSON object
        run path: RequestBodyEvaluationException propagates → fail-fast ERROR row
        preview path: downgraded to REQUEST_BODY_EVALUATION_ERROR warning, content = null
```

Transaction boundaries are unchanged — validation and extraction run inside the existing `TestSuiteService` create/update transaction; the evaluator is DB-free.

## Risks / Trade-offs

- **Forgetting the explicit `jsonataContent` scan in `TemplateVariableExtractor` (D4)** → silent, compile-clean loss of `${{}}` extraction for JSONata bodies. Mitigation: dedicated unit test in `TemplateVariableExtractorTest` asserting a variable is extracted from `jsonataContent` with source `BODY`, plus a functional assertion that a JSONata suite with a bound `${{}}` variable validates as valid and reports no orphan binding.
- **Pre-existing dev/branch data with String `content`** → read-time Jackson deserialization failure on affected suites. Mitigation: documented in Migration Plan (one-off SQL fix or local DB reset); acceptable because the feature is unreleased.
- **Both-set slipping through some other write path** → an author could get a body they did not intend. Mitigation: the check sits in the shared `validateRequestTemplateBody`, reached by both create and update; the evaluator's defensive precedence (`jsonataContent` wins) makes the outcome deterministic rather than arbitrary.
- **Mechanical test churn (~15 files) hides a semantic regression** → a `.content(str)` → `.jsonataContent(str)` sweep is easy to apply and easy to get subtly wrong (e.g. converting a Map-authored case). Mitigation: `RequestBodyDtoSerializationTest` pins the wire shape for both fields; `JsonataRequestTemplateFunctionalTests` and the `MultiTurn*` suites pin end-to-end behavior; both are run, not just compiled.
- **Trade-off — two nullable fields instead of one non-null carrier**: exclusivity is a runtime rule, not a type-system guarantee. Accepted for the contract clarity and OpenAPI typing it buys (D1).

## Migration Plan

1. Land runner-core (DTO + evaluator + resolver) and EF backend (validator + extractor) together — the module boundary means a partial landing does not compile.
2. Update the three `*-jsonata-body.json` OpenAPI examples and the AGENTS.md "Request-template JSONata evaluation seam" bullet (which currently describes the `Map`|`String` union) in the same change.
3. Dev-data step (no production equivalent — feature unreleased): any suite on this branch persisted with a String `content` must be fixed one-off or the local DB reset. Detection: `request_template->'body'->'content'` is a JSON string.
4. Rollback: revert the change set; the union-typed DTO accepts both the old and the new payloads' `content`, but a suite saved with `jsonataContent` would lose its body on the reverted code (unknown property), so rollback after suites have been authored requires the same one-off data fix in reverse.
5. FE handoff: communicate the new shape before FE integration starts.

## Open Questions

None. Field name (`jsonataContent`), exclusivity semantics (400 when both set, no body when both null), and the hard-break decision are all settled in the proposal.
