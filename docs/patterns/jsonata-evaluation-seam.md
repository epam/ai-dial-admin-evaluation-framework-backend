# Request-template JSONata evaluation seam

A request template's JSON `body` carries two mutually exclusive fields on `JsonRequestBodyDto`:

- `content` (`Map<String, Object>`) — legacy structural `${{}}` resolution, then serialized. A plain JSON body evaluates to itself, since JSON is a syntactic subset of JSONata.
- `jsonataContent` (`String`) — the JSONata source; `${{}}` placeholders preprocessed into the raw text via `JsonataSourcePreprocessor`'s quote-state scanner, then the combined text evaluated directly.

Both set → HTTP 400 (`VALIDATION_ERROR`) at write time.

Both fields converge, unconditionally, on `RequestBodyEvaluator` → `JsonataEvaluationService.evaluate(expression, jsonData, Map<String, Object> bindings)` (frame bindings; a Java `null` value binds JSONata's explicit-null sentinel `Jsonata.NULL_VALUE`, not an unbound variable — so `$append`-style expressions get null-append, not undefined-append, semantics for a column that failed extraction).

The evaluated result MUST be a JSON object; a non-object result or an evaluation failure is a `REQUEST_BODY_EVALUATION_ERROR` (write-time: HTTP 400 for a syntactically invalid `String` source; run-time: an ERROR row, never a suite-validation failure).

## FILE placeholders: one decision point

Whether a full-value placeholder (`"${{doc}}"`, or a bare `${{doc}}` in `jsonataContent`) is converted to a DIAL data ref (`files/<bucket>/...` via `DialFileRefResolver.resolveToDialRef`) is decided in exactly one place, `runner.service.FilePlaceholderDetector.isFileReference(typeHint, binding, testCaseSchema)`, shared by `TemplateContentResolver.resolveObject`, `JsonataSourcePreprocessor` and `McpRequestResolver`:

1. An explicit hint wins (trimmed, case-insensitive): `|file` converts, any other hint (e.g. `|string`) does not.
2. No hint: convert iff the binding's `dataField` names a `FILE` column in the test-case schema.
3. `constantValue` bindings, unknown columns and a null/empty schema never convert without an explicit `|file`.

Only a `String` resolved value is converted; other types pass through. Placeholders embedded in surrounding text stay stringify-only. The schema is threaded explicitly (`RequestResolver.resolve`/`resolveForRun`, `RequestBodyEvaluator.evaluate`, `JsonataSourcePreprocessor.preprocess`, `McpRequestResolver.resolve`): `EvaluationContext.snapshotTestCaseSchema` on the run path, `DatasetSchemaProvider.getSchema(datasetId)` for preview and try-it-out. A new call site that forgets it silently loses the schema-type rule, so there are no schema-less overloads.

Exceptions, deliberately outside the rule (hintless detection applies only where the explicit hint already works):
- **Multipart `FILE` parts**: `RequestResolver.resolveMultipartBody` passes no schema. The multipart serializer materializes the raw `@ef/...` ref itself and rejects a `files/` ref, so the correct template is the hintless `${{doc}}`. `TEXT` parts get the schema.
- **URL-encoded values**: resolved with `resolveString` (stringify-only, hints ignored), so they never convert.

**Try-it-out with variables**: variables mode turns each typed value into a `constantValue` binding, which has no column. `TryItOutVariableBindings.bindToFileColumns` restores a `dataField` binding (value moved into the data map) when the suite binds that variable to a `FILE` column, so variables mode matches a real run. Variables bound to non-FILE columns, constants or nothing keep needing an explicit `|file`.

## Response-column extraction bindings

`response` columns are extracted with the same seam plus additive `$_request`/`$_response` frame bindings (parsed sent-request/received-response JSON; the root document stays the raw response body, so pre-existing expressions are unaffected). The leading underscore is deliberate — it keeps the natural names `request`/`response` free for user columns, and it is a valid JSONata identifier character.

**Never give a frame binding a name containing `.`** — `.` is a registered tokenizer operator, so `frame.bind("frame.request", …)` is accepted silently but no expression syntax can ever read it back (`$frame.request` lexes as variable `frame` + field access), yielding `undefined` with no error and no extraction warning.

`JsonataProperties` (`jsonata.evaluation-timeout-ms`/`jsonata.max-recursion-depth`) bounds every evaluation via `Frame.setRuntimeBounds`.

A response column name colliding with a JSONata built-in function name or the reserved `_request`/`_response` frame names is rejected at write time (`runner.constants.JsonataReservedNames`, which owns both the binding-name constants and the reserved set so they cannot drift); plain `request`/`response` are allowed column names.

See [request-template](../../openspec/specs/request-template/spec.md) and [response-columns](../../openspec/specs/response-columns/spec.md) specs.
