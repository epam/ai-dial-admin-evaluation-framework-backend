## WP0 — Spike + change bundle (done)

- [x] 0.1 `JsonataFrameSpikeTest` (`src/test/java/com/epam/aidial/evaluation/service/domain/`) pins: unbound
  frame variable → undefined; `$append` undefined-append semantics; `$append` null-append semantics
  (correction: requires binding `Jsonata.NULL_VALUE`, not Java `null` — see design.md F2); `createFrame`
  + `bind` for Map/List/String/Number; `setRuntimeBounds` aborts a runaway expression and passes a trivial
  one under generous bounds; JSON-echo fidelity for a legacy chat-completions body (pins F1); a spliced
  JSON array literal inside a larger JSONata expression, and a JSON-string-escaped value inside a string
  literal staying plain text. All 10 tests green (`./gradlew test --tests
  "com.epam.aidial.evaluation.service.domain.JsonataFrameSpikeTest"`).
- [x] 0.2 This change bundle (`proposal.md`, `design.md`, delta specs, this `tasks.md`).

## WP1 — JSONata evaluation seam + properties

- [x] 1.1 Add a `Frame`-aware `evaluate(String expression, String jsonData, Frame frame)` overload (or
  equivalent `Map<String, Object> bindings` overload that internally builds the `Frame`) to
  `JsonataEvaluationService` / `DashjoinJsonataEvaluationService`, reusing the spike's confirmed
  `createFrame()` + `bind()` + `evaluate(input, frame)` sequence. Unit test: a bound variable is reachable
  from the expression; an unbound one is `null`.
- [x] 1.2 New `JsonataProperties` (`@ConfigurationProperties(prefix = "jsonata")`,
  `evaluationTimeoutMs`, `maxRecursionDepth`) with `@Validated`/`@Min` — no field-initializer defaults.
  Defaults in `application.yml`. Wire into the evaluation seam via `Frame.setRuntimeBounds`.
- [x] 1.3 Add `jsonata.evaluation-timeout-ms` and `jsonata.max-recursion-depth` rows to
  `docs/configuration.md` (all six columns).
- [x] 1.4 Unit test: an expression exceeding the configured bounds throws (reusing the spike's runaway
  expression); a normal expression is unaffected.

## WP2 — Request body model + resolver refactor

- [x] 2.1 `JsonRequestBodyDto.content`: `Map<String, Object>` → `Object`. Update `@Schema`
  examples/OpenAPI to `oneOf` [object, string].
- [x] 2.2 `JsonataSourcePreprocessor` (new, `service.domain`): textual `${{}}` placeholder substitution
  over a raw body-text string per the three substitution modes (quoted-full-value → JSON-serialize;
  embedded-in-literal → JSON-string-escape; bare → JSON-serialize). Unit tests per mode plus the
  placeholder-injection-precursor scenario from the spike.
- [x] 2.3 `TemplateContentResolver` (new/refactor, `service.domain`): Map-content path (existing
  structural resolution → serialize) and String-content path (preprocess → pass through) converge on one
  body-text output. Unit tests for both content variants.
- [x] 2.4 `RequestBodyEvaluator` (new, `service.domain`): evaluates the resolved body text as JSONata
  (via WP1's seam) with the request-template frame (previous turn's extracted columns, Decision 5),
  validates the result is a JSON object, else signals ERROR. Unit tests: object result passes through;
  non-object result and evaluation exception both signal ERROR.
- [x] 2.5 Wire `ResolvedRequestService`'s preview path (`GET .../resolved-request`) through
  `RequestBodyEvaluator` so the preview reflects the JSONata-evaluated body. Update
  `ResolvedRequestDto`/`ResolvedJsonBodyDto` OpenAPI examples if the preview shape changes.
- [x] 2.6 Functional/unit test: an existing Map-content suite with no JSONata-specific syntax produces a
  byte-identical resolved body before/after this refactor (regression guard for the JSON ⊂ JSONata claim).

## WP3 — Reserved names + validation 400s

- [x] 3.1 `JsonataReservedNames` (new constants class, `constants` or `service.domain`): the JSONata
  built-in function name set (reuse the existing query-DSL function registry's names — do not
  re-enumerate) plus `request`/`response`.
- [x] 3.2 `TestSuiteRequestValidator`: reject (HTTP 400) a `String`-content request body that fails
  `jsonataEvaluationService.validateExpression`; reject a `responseColumns[i].name` colliding with
  `JsonataReservedNames`. Unit tests for both 400s and the accepted (non-colliding, valid-JSONata) cases.
- [x] 3.3 `TemplateVariableExtractor` (or equivalent): unit test pinning that `String`-content placeholder
  extraction still finds `${{var}}` occurrences inside JSONata source text (extraction is unaffected by
  the content type change).

## WP4 — Response-column extraction frame

- [x] 4.1 `DashjoinJsonataEvaluationService`/`JsonataEvaluationService`: add a 3-arg
  evaluate-with-`$request`/`$response`-frame variant, root document unchanged (raw response body).
  Document the narrow exception to the "only importer" invariant here (per design.md F2) if this
  component is also where `Jsonata.NULL_VALUE` bindings are populated for the request-template frame.
- [x] 4.2 `ResponseColumnExtractor.ExtractionResult`: carry through reconciled values in a form
  `RequestBodyEvaluator`/`TurnLoopExecutor` can bind onto the next turn's frame (not just the serialized
  JSON strings used for persistence today).
- [x] 4.3 `ResponseColumnExtractor.extract(...)`/`buildResult` pass-through: when a column's extraction
  genuinely failed (JSONata error or type-mismatch), bind `Jsonata.NULL_VALUE` (not Java `null`) for that
  column's frame slot so downstream `$append`-style expressions see real null-append semantics, per the
  design.md F2 correction. Unit test asserting this distinction (mirrors the spike's null-vs-NULL_VALUE
  pinning) at the `ResponseColumnExtractor`/frame-population layer.
- [x] 4.4 Unit test: `$request`/`$response` frame variables are reachable and structurally correct;
  existing response-column expressions (no `$request`/`$response` reference) are unaffected.

## WP5 — Streaming turn invocation + error-envelope parity

- [x] 5.1 `DeploymentTurnInvoker`: remove the non-streaming-only constraint; invoke deployments with
  streaming enabled for every turn (reuse the single-turn SSE invocation path instead of the dedicated
  non-streaming call).
- [x] 5.2 `TurnOutcome`: add `logDetails` (or equivalent) so a streaming turn failure carries the same
  diagnostic detail a single-turn SSE failure would, matching single-turn's error-envelope shape.
- [x] 5.3 Remove `content.put("stream", false)` force-injection from the turn body-assembly path.
- [x] 5.4 Unit/functional test: a multi-turn suite streams each turn and still produces the same persisted
  `responseBody` shape as the prior non-streaming path for an OpenAI-mode response.

## WP6 — Unified turn loop + EvaluationContext schema + worker dispatch

- [x] 6.1 `EvaluationContext`: add `snapshotTestCaseSchema` (`List<FieldDefinitionDto>`, sourced from
  `SuiteSnapshotDto`, builder field like existing `snapshot*` members).
- [x] 6.2 `PerTurnBindingDetector` (new, `service.domain.job`): given the effective `inputBindings` and
  `snapshotTestCaseSchema`, returns whether at least one bound `dataField` has `perTurn = true`. Unit
  tests: no bindings → false; all-shared bindings → false; one per-turn binding → true.
- [x] 6.3 `TurnLoopExecutor` (new, `service.domain.job`): replaces `MultiTurnExecutor`'s fixed-`N` loop
  with `N = perTurnBindingDetector.hasPerTurnBinding(...) ? multiTurnData.length : 1`; single-turn cases
  keep `turnIndex`/`totalTurns = null`. Reuses `DeploymentTurnInvoker`/`RequestBodyEvaluator`/
  `ResponseColumnExtractor` from WP2/WP4/WP5. Delete the now-dead `messages`-array/`choices[0].message`
  special-casing from the old `MultiTurnExecutor.runTurn`.
- [x] 6.4 `EvaluationWorker.execute` dispatch: route every DEPLOYMENT HTTP case (single-turn and
  multi-turn) through `TurnLoopExecutor`; delete the now-unreachable direct single-turn body-resolution
  path if `TurnLoopExecutor`'s `N = 1` branch fully subsumes it (verify behavior parity first via WP2.6's
  regression test).
- [x] 6.5 Functional test: a multi-turn dataset bound to a suite with **no** per-turn binding produces
  exactly one request/result row per test case (collapse case, Decision 4), not `multiTurnData.length`
  rows.
- [x] 6.6 Functional test: a multi-turn dataset bound to a suite **with** a per-turn binding produces `N`
  result rows with history accumulated via the frame (Decision 5), replacing the old hardcoded-history
  functional coverage.

## WP7 — DIAL custom-content streaming accumulation

- [x] 7.1 `CustomContentAccumulator` (new, `service.domain.job`): merges `choices[i].delta.custom_content`
  across SSE chunks — scalar fields overwrite, `attachments`/`stages` arrays merge by index preserving
  fields present in one partial but not another. Unit tests per merge rule.
- [x] 7.2 Wire `CustomContentAccumulator` into `StreamingResponseAccumulator`'s OpenAI-mode assembly so the
  final assembled body carries `choices[0].message.custom_content`.
- [x] 7.3 Unit test: a multi-chunk stream with `custom_content` on different chunks assembles onto
  `choices[0].message.custom_content` identically to a non-streaming DIAL response's `custom_content`.

## WP8 — Functional tests, spec sync, docs

- [x] 8.1 Functional test suite: Map-content single-turn suite unchanged behavior (regression, new
  `JsonataRequestTemplateFunctionalTests`); String-content JSONata body suite (non-chat-completions
  Responses-API-shaped, new); multi-turn suite with per-turn binding (N turns, history via frame — covered
  by `MultiTurnRunFunctionalTests`) vs. without (N=1 collapse, new); streaming DIAL custom-content
  accumulation across turns (new); reserved-name 400s at suite save time and JSONata write-time 400 for
  invalid String-content source (already covered by `ResponseColumnFunctionalTests`, not duplicated).
- [ ] 8.2 Sync delta specs (`multi-turn-test-case`, `request-template`, `response-columns` under this
  change's `specs/`) into `openspec/specs/` via `/opsx:sync` — verify with `git diff` that main specs
  gained content and did not lose existing requirements. Deferred to `/opsx:archive` per
  `openspec/config.yaml` `rules.archive` ("Delta spec sync" bullet) — not performed as part of WP8 itself;
  the delta specs under this change's `specs/` were reviewed and corrected for accuracy in the meantime
  (see WP8 notes below).
- [x] 8.3 Update `AGENTS.md`: replace the `multi-turn-test-case` inline convention's "Assistant reply path
  is the hardcoded OpenAI `choices[0].message`; turns are always non-streaming" line with the JSONata
  frame-driven / streaming description; add the request-template JSONata evaluation seam as a new inline
  convention or linked pattern doc if it's substantial enough (per AGENTS.md Maintenance guidelines).
- [x] 8.4 Update OpenAPI examples for `RequestBodyDto` (String-content variant) and any changed
  `ResolvedRequestDto`/`ResolvedJsonBodyDto` examples.
- [ ] 8.5 Update `openspec/specs/README.md` summaries for `multi-turn-test-case`, `request-template`,
  `response-columns` if their one-line summaries become materially inaccurate after sync (per Spec Index
  Maintenance Policy). Deferred to `/opsx:archive` alongside 8.2 (README auto-sync is an archive-time
  step per `rules.archive`, and depends on the sync in 8.2 having happened first).
- [x] 8.6 Run `./gradlew clean build` (full build: tests + Checkstyle + Spotless check + ArchUnit/
  LayeredArchitecture + JooqSchemaDrift + LoggingConvention) and confirm green before archiving.
