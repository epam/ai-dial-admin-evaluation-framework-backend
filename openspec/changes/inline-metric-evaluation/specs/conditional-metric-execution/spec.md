## MODIFIED Requirements

### Requirement: Runtime condition outcome
At runtime the condition SHALL map to one of three outcomes: clean boolean true → the metric runs; clean boolean false → the metric is skipped and omitted entirely (no `metricValues`/`metricInfos` entry); any other outcome (thrown, non-boolean, or null) → the metric is skipped but surfaced as a metric-level error (`metricError::<name>`), and the result row's execution status stays SUCCESS. Conditions are only evaluated on SUCCESS result rows. Under inline metric evaluation (see `metric-evaluation`), the broken-condition outcome additionally aborts the request chain — remaining turns and later requests are skipped — while the row itself still stays SUCCESS and still carries the `metricError::<name>` entry; this is a mode-scoped divergence from the non-inline behavior, where a broken condition never affects anything beyond that one metric's own entry.

Status: **Planned**

#### Scenario: False omits the metric
- **WHEN** a condition evaluates to false
- **THEN** the metric contributes no entry to `metricValues` or `metricInfos`, and the eval summary remains SUCCESS

#### Scenario: Broken condition surfaces as a metric error without failing the row
- **WHEN** a condition throws or returns a non-boolean, on a non-inline run
- **THEN** the metric is skipped, a `metricError::<name>` entry is recorded, and the result row stays SUCCESS, and no other metric or later request is affected

#### Scenario: Broken condition aborts the chain under inline evaluation
- **WHEN** a condition throws or returns a non-boolean, and the TSMD is evaluated inline (the run is inline-mode)
- **THEN** the metric is skipped, a `metricError::<name>` entry is recorded, the row stays SUCCESS, **and** the chain aborts — remaining turns and any later requests SHALL NOT execute

## ADDED Requirements

### Requirement: `$_metrics` is not available inside a condition expression
A TSMD's `condition` expression SHALL NOT have access to the `$_metrics` frame. `ConditionExpressionEvaluator` evaluates `condition` against its own four-namespace dictionary (`data`, `response`, `turn`, `request`) only; a `condition` referencing `$_metrics` SHALL have that reference resolve to plain JSONata `undefined`. Whether that `undefined` produces the "broken condition" runtime outcome depends on the enclosing expression, not on the reference by itself: a bare reference (e.g. `` $_metrics.`judge`.score.value ``) makes the condition's own overall result `undefined` — a non-boolean outcome, which IS the broken-condition case (a `metricError::<name>` entry; chain-abort under inline mode per the "Runtime condition outcome" requirement) — whereas wrapping the same reference in `$exists(...)` (e.g. `` $exists($_metrics.`judge`.score.value) ``) yields a clean boolean `false`, the ordinary "condition is false" outcome: the metric is simply omitted and, under inline mode, the chain continues rather than aborting. No new write-time validation SHALL reject a `condition` that references `$_metrics` — this is a documented runtime limitation, not a 400 at write time.

Status: **Planned**

#### Scenario: Bare $_metrics reference resolves undefined and triggers the broken-condition outcome
- **WHEN** a TSMD's `condition` is `` $_metrics.`judge`.score.value `` and the suite is inline-mode
- **THEN** `$_metrics` inside the condition dictionary resolves to `undefined`, the condition's overall result is that non-boolean `undefined`, and this produces the broken-condition runtime outcome for that TSMD (a `metricError::<name>` entry, and the chain aborts under inline evaluation)

#### Scenario: $exists-wrapped $_metrics reference resolves a clean false
- **WHEN** a TSMD's `condition` is `` $exists($_metrics.`judge`.score.value) `` and the suite is inline-mode
- **THEN** `$_metrics` inside the condition dictionary still resolves to `undefined`, but `$exists(undefined)` evaluates to a clean boolean `false` — the metric is omitted for that row and, unlike the bare-reference case, the chain continues

#### Scenario: No write-time rejection for a condition referencing $_metrics
- **WHEN** a TSMD is created or updated with a syntactically valid `condition` that references `$_metrics`
- **THEN** the write SHALL succeed (HTTP 201/200) — write-time validation checks only JSONata syntax, not namespace availability
