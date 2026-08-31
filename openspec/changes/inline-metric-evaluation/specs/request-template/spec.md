## ADDED Requirements

### Requirement: `$_metrics` frame variable available to a JSON request body
For an inline run (see `metric-evaluation`), a request's JSON body (`content` or `jsonataContent`) SHALL be JSONata-evaluated with an additional bound frame variable, `$_metrics`, holding every TSMD output evaluated inline so far in the same test-case execution (see `multi-request-suite` for chain accumulation). The shape mirrors `MetricOutputFieldDto`: `$_metrics.<tsmdName>.<outputField>` resolves to `{"value": <number|null>, "details": <object|null>}` on success or `{"error": "<msg>"}` on a per-field error; `$_metrics.<tsmdName>.error` carries a wholesale metric error (e.g. a `ConditionError` or a `Failure` without per-field detail). A TSMD name or output field containing a space or a `.` MUST be backtick-quoted in the expression (e.g. `` $_metrics.`RAG Quality`.faithfulness.details.reason ``); a TSMD name containing a backtick character is unaddressable from `$_metrics` — this is a documented authoring limitation, not a validated restriction. An unresolvable path (a TSMD or field that has not yet produced a value at this row) SHALL resolve to JSONata `undefined`, exactly like any other unbound frame reference — no error is raised for this. `$_metrics` follows the same "unconditional JSONata evaluation of JSON request bodies" and "runtime object contract for evaluated request body" rules as every other frame variable: it does not change whether evaluation happens, only what is available inside it. For a non-inline run, `$_metrics` SHALL NOT be bound at all — a reference to it resolves to `undefined` exactly as an unbound frame variable would.

Status: **Planned**

#### Scenario: Request body reads an earlier request's metric field
- **WHEN** request #1's `jsonataContent` body contains `` {"priorReason": $_metrics.`judge`.score.details.reason} `` and request #0's inline-evaluated `judge` TSMD produced that field
- **THEN** the resolved and evaluated body for request #1 SHALL contain that field's value at `priorReason`

#### Scenario: Unresolvable metrics path is undefined, not an error
- **WHEN** a request body references `` $_metrics.`notYetRun`.score.value `` for a TSMD that has not been evaluated on any prior row of this execution
- **THEN** the reference resolves to JSONata `undefined`, and evaluation proceeds under the same "runtime object contract" rules as any other undefined reference (the enclosing object simply omits that key, unless the body's overall JSON shape becomes non-object, in which case the row is `ERROR`)

#### Scenario: Non-inline run never binds $_metrics
- **WHEN** a run is non-inline
- **THEN** a request body referencing `$_metrics` SHALL see it as an unbound variable (`undefined`), identical to referencing any other name nothing has ever bound

### Requirement: A present-null metric field cannot be transmitted in the emitted body
`$_metrics` is constructed by serializing an `ObjectNode` (using `putNull` for genuinely-null fields) and re-parsing it into the frame, so a present-but-null field IS distinguishable from an absent one for JSONata purposes — `$exists($_metrics.judge.score.value)` returns `true` for a present null. However, once a request body's evaluated JSON object is serialized for transmission, the shared `ObjectMapper`'s `NON_NULL` content inclusion applies to that outgoing body exactly as it does everywhere else in the system: a key whose evaluated value is JSON `null` SHALL be dropped from the emitted request, not sent as an explicit `null`. This SHALL be documented as a known caveat, not treated as a defect — an author who needs the deployment to see an explicit `null` for a metric-derived field cannot rely on this path.

Status: **Planned**

#### Scenario: Present-null metric field is visible to JSONata but dropped from the emitted body
- **WHEN** a request body's evaluated JSON object has a key whose value came from `$_metrics.judge.score.value` and that value is JSON `null`
- **THEN** `$exists(...)` on that path evaluates `true` during body evaluation, but the key SHALL NOT appear at all in the serialized request body actually sent to the deployment
