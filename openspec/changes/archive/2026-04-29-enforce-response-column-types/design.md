## Context

Response column extraction lives at `service/domain/ResponseColumnExtractor.java`. It evaluates a JSONata expression against a stringified response body and persists the raw result on `test_case_run_results.extracted_columns` (JSONB). The extracted value is later read by `MetricEvaluationWorker.buildRequest()` and bound — via `BindingResolver` — into the metric provider's `EvaluationRequestDto.input` map.

JSONata's "result sequence flattening" rule (per the JSONata language spec, faithfully implemented by `com.dashjoin:jsonata`) collapses a single-element result sequence to the bare value:

| Matches in response | JSONata result | Today's stored cell |
|---|---|---|
| 0 | `undefined` | `null` |
| 1 | the value itself (NOT a singleton) | the bare scalar |
| ≥2 | array of values | array |

`ResponseColumnDefinitionDto` already carries a declared `type: SchemaFieldType` (one of `STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`, `OBJECT`, `ARRAY`, `FILE`), but `ResponseColumnExtractor` does not consult it — JSONata's output is stored verbatim.

The metric service receives the wrong shape only on N=1 rows; N≥2 rows succeed. This produces flaky-looking failures (issue #883: `Row 000013` only) and an opaque pydantic `422 list_type` error far from the root cause.

The existing extractor already has a per-column `try/catch (Exception)` that turns extraction failures into `{cell: null, warnings += ExtractionWarningDto}`. This change rides that path: type mismatches become "extraction failures" too.

## Goals / Non-Goals

**Goals:**
- Make `ResponseColumnDefinitionDto.type` the contract: extracted values either match the declared shape or fail the cell with a structured warning.
- Fix issue #883 (single-element list flattening) by silently wrapping ARRAY+scalar results into singletons.
- Keep the visible failure UX consistent with today: `null` cell + an entry in `extraction_warnings`.
- Apply uniformly to both DEPLOYMENT and MCP_TOOL evaluation paths (both go through `ResponseColumnExtractor`).

**Non-Goals:**
- Schema-aware reconciliation in `BindingResolver` against the metric provider's `inputSchema` (out of scope; see Risks).
- Backfilling or rewriting existing `extracted_columns` rows.
- Changes to CSV import (`csv/SchemaTypeCoercer`). One scoped test will document current behavior; a separate change can address it if needed.
- Run-level summarization of extraction warnings (warnings remain per-row).
- Coercion across exotic JSON types (e.g., interpreting `{"value": "42"}` as `NUMBER`).

## Decisions

### D1: New component `ResponseColumnTypeReconciler` (vs reusing `csv/SchemaTypeCoercer`)

A new injectable `@Component` `service.domain.ResponseColumnTypeReconciler` performs reconciliation. It is *not* an extension of `csv/SchemaTypeCoercer`.

**Rationale:** `SchemaTypeCoercer` exists for CSV cell parsing where `OBJECT`/`ARRAY` cells *already are* parsed JSON values, so its no-op for those types is correct in that context. Conflating CSV semantics with JSONata-result semantics would force the existing CSV behavior to change or introduce a flag — adding accidental complexity. JSONata-result reconciliation has different inputs (Java objects from a JSONata library), different policy (singleton wrapping), and a different failure mode (throw vs. silent pass-through). A separate component keeps both call sites honest.

**Alternative considered:** A static utility on `SchemaFieldType`. Rejected — Spring `@Component` matches the project pattern (`service.domain.*` components are top-level injectable classes per AGENTS.md / layering rules) and lets unit tests inject a mock if extraction is ever orchestrated by a higher-level service.

### D2: Reconciliation policy — coerce when safe, throw otherwise

| Declared `type` | JSONata result type | Outcome |
|---|---|---|
| `ARRAY` | `null` | `null` (pass-through) |
| `ARRAY` | array (JSON or `List`) | as-is |
| `ARRAY` | scalar (string/number/boolean) | wrap to singleton — **silent** |
| `ARRAY` | object (`Map`/`ObjectNode`) | wrap to singleton — **silent** |
| `STRING` / `FILE` | `null` | `null` |
| `STRING` / `FILE` | string | as-is |
| `STRING` / `FILE` | number/boolean | `String.valueOf` — silent |
| `STRING` / `FILE` | array/object | **throw `TypeMismatchException`** |
| `INTEGER` | `null` | `null` |
| `INTEGER` | `Long`/`Integer` | as-is (normalize to `Long`) |
| `INTEGER` | whole `Double` | `longValue()` — silent |
| `INTEGER` | parseable string | `Long.parseLong` — silent |
| `INTEGER` | fractional `Double`, boolean, array, object | **throw** |
| `NUMBER` | `null` | `null` |
| `NUMBER` | any `Number` | `doubleValue()` — silent |
| `NUMBER` | parseable string | `Double.parseDouble` — silent |
| `NUMBER` | boolean, array, object | **throw** |
| `BOOLEAN` | `null` | `null` |
| `BOOLEAN` | `Boolean` | as-is |
| `BOOLEAN` | `"true"`/`"false"` (case-insensitive) | parse — silent |
| `BOOLEAN` | other | **throw** |
| `OBJECT` | `null` | `null` |
| `OBJECT` | `Map` / `ObjectNode` | as-is |
| `OBJECT` | scalar / array | **throw** |
| `null` (declared type missing) | anything | as-is — defensive (TestSuiteService normalizes default to `STRING`, but if data is malformed, do not block extraction) |

**Rationale:**
- `ARRAY`+scalar wrapping is the bug fix for #883 and is silent because it reflects JSONata's documented flattening — the user's expression is "correct," the language quirk is what's being papered over.
- Scalar-to-scalar coercions (e.g., `NUMBER` ← parseable string) are silent because they are unambiguously safe and match how JSON consumers typically interpret such values.
- Cross-shape mismatches (object vs array, scalar vs object) cannot be safely coerced; throwing routes them through the existing `ExtractionWarningDto` pipeline and surfaces them on the run result.

**Alternatives considered:**
- *Pure pass-through with warnings* — original lean. Rejected: silent-data-corruption risk; user has to inspect `extracted_columns` JSONB to notice.
- *Pure strict (throw on any mismatch including `ARRAY`+scalar)* — rejected: would punish JSONata users for the language's flattening rule, breaking a large fraction of valid expressions.

### D3: Throw + reuse existing handler in `ResponseColumnExtractor`

`ResponseColumnTypeReconciler.reconcile(value, type)` throws `TypeMismatchException` (a new domain exception under `service.domain.exception`). The existing `catch (Exception ex)` block in `ResponseColumnExtractor.extract()` already produces `{cell: null, warnings += ExtractionWarningDto.builder().column(...).expression(...).error(ex.getMessage()).build()}` — the new exception is caught there with no extra wiring.

**Rationale:**
- Zero new branches in `ResponseColumnExtractor`.
- Identical observable behavior to today's "JSONata expression failed" error path → consistent UX for users (one place to look: `extraction_warnings`).
- The catch block already logs `log.warn(..., ex)` with the exception as the trailing arg (per AGENTS.md SLF4J convention).

**Note on the catch:** `ResponseColumnExtractor` currently uses `catch (Exception ex)`, which violates the AGENTS.md "catch specific exceptions" guidance. We could narrow it (catch `JsonataEvaluationException`, `TypeMismatchException`, `IllegalStateException`, …), but doing so is *out of scope* of this change to avoid scope creep. Document this as an "Open Questions" item for a follow-up cleanup.

**Catch-propagation guard:** Because the contract relies on the existing broad catch absorbing `TypeMismatchException`, an explicit unit test in task 4.2(d) asserts that a `TypeMismatchException` thrown from the reconciler surfaces as an `ExtractionWarningDto` (not a thrown exception). If a future change narrows the catch, this test will fail and force the maintainer to keep `TypeMismatchException` in the caught set.

### D4: Error message format

`TypeMismatchException` constructor receives the declared type and the actual JSON node type, and produces a deterministic message:

```
Type mismatch: expected ARRAY, got STRING
Type mismatch: expected NUMBER, got OBJECT
```

When the actual value is a parseable-string-with-failure (e.g., `INTEGER` ← `"abc"`), include the raw value (truncated to ~80 chars):

```
Type mismatch: expected INTEGER, got STRING ("abc") — not parseable as integer
```

This message becomes `ExtractionWarningDto.error` and is surfaced verbatim on the run-result API and CSV export.

### D5: Type detection of JSONata results

JSONata-via-dashjoin returns a Java object graph: `String`, `Long`/`Double`/`BigInteger`/`BigDecimal`, `Boolean`, `java.util.Map<String,Object>`, `java.util.List<Object>`, or `null`. The reconciler dispatches on `instanceof` checks (no Jackson conversion required upfront — the extractor already converts to `JsonNode` *after* reconciliation via `objectMapper.convertValue(value, JsonNode.class)` at line 71).

**Rationale:** Operating on the raw Java types is cheaper than round-tripping through `JsonNode` and avoids ambiguity around `IntNode` vs `LongNode` vs `DoubleNode`. The existing line 71 conversion happens once, after reconciliation.

## Risks / Trade-offs

**[R1] Existing runs whose JSONata expressions accidentally relied on the wrong shape will now produce visible warnings instead of "succeeding."**
→ Mitigation: this is the intended behavior. The warning carries column name + expression + a precise mismatch message — actionable. No behavior regresses for *correctly-typed* extractions.

**[R2] Lost-data debugging on mismatch:** a strict throw replaces the wrong-shape value with `null` in storage. The user keeps the warning + expression but loses the raw value.
→ Mitigation: the `error` message includes a truncated repr of the actual value when feasible (D4). For deeper debugging, `responseBody` is also persisted on the run result; the user can re-evaluate the expression against the stored response. Acceptable trade-off vs the silent-corruption risk of pass-through.

**[R3] Metric provider input schema is still not consulted.** A binding may map `extractedColumns.foo` (declared `STRING` and reconciled to `String`) into a metric input parameter that requires `array`. The metric service still 422s.
→ Mitigation: out of scope; track as a follow-up. The reported bug (#883) is upstream of this case — declared `ARRAY` columns being collapsed — and is fully fixed by this change. Mismatches between *column type* and *binding target type* are a separate failure mode that today the user has full control over (they configure both the column type and the binding).

**[R4] Reconciler decisions hardcode coercion semantics for booleans/numbers from strings.**
→ Mitigation: documented in the spec scenarios. If users object, the policy can be tightened in a follow-up (e.g., disallow `BOOLEAN` ← string). The semantics chosen mirror `csv/SchemaTypeCoercer` for consistency.

**[R5] The catch block in `ResponseColumnExtractor` is `catch (Exception)`, broader than ideal.**
→ Mitigation: documented as an Open Question; does not block this change. Narrowing it would require auditing every exception path through `JsonataEvaluationService` + the new reconciler — a separate refactor.

**[R6] No migration of existing `extracted_columns` JSONB.**
→ Confirmed acceptable per user direction. Pre-existing rows that already store wrong shapes remain stored. Re-running the test case re-evaluates and produces the reconciled value.

## Migration Plan

No deployment or rollback complexity:
- New code path is purely additive (one new component, one new exception class, one method call inserted in `ResponseColumnExtractor`).
- No DB migration, no config change, no API contract change.
- Roll-forward only — if reconciliation logic needs adjustment, ship a follow-up.

## Open Questions

- Should `ResponseColumnExtractor`'s `catch (Exception)` be narrowed in the same change, or split into a separate cleanup? *Lean: separate cleanup; out of scope here.*
- Should we surface "row had extraction warnings" at the run-summary level (e.g., a counter on `TestSuiteRun`)? *Lean: defer; per-row warnings already exist; revisit if users report difficulty noticing them.*
- Is the CSV-import side (`SchemaTypeCoercer` no-op for `ARRAY`/`OBJECT`) an actual bug? Test from this change will document current behavior; decision on whether to fix lives in a follow-up change.
