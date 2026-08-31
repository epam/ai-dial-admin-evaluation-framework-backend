## ADDED Requirements

### Requirement: `$_metrics` resolves undefined in try-it-out preview
The try-it-out preview path (`ResolvedRequestService`/`TryItOutService.runChain`) does not run inline metric evaluation — it never dispatches a metric provider `/evaluate` call, and it never resolves an `Expression` metric binding at all (there is no `MetricProviderClient`/metric-binding collaborator anywhere in the try-out chain). A request body evaluated during try-it-out that references `$_metrics` SHALL therefore see it as an unbound frame variable, resolving to JSONata `undefined`, regardless of whether the suite would be classified inline-mode for a real run. This is a documented divergence between the preview and a real run, not a defect: giving try-it-out a cost-bearing metric-provider call for an interactive preview is an explicit non-goal of this change (tracked as a follow-up).

Status: **Planned**

#### Scenario: Preview of an inline-mode suite does not evaluate metrics
- **WHEN** a user calls try-it-out on a test case belonging to a suite that would be classified inline-mode for a real run
- **THEN** the preview SHALL NOT make any metric-provider `/evaluate` call, and any `$_metrics` reference in the previewed request body SHALL resolve to `undefined`

#### Scenario: Multi-turn preview still sees no $_metrics across turns
- **WHEN** a multi-turn test case's try-it-out preview resolves turn k+1's body referencing `$_metrics` from turn k
- **THEN** that reference SHALL resolve to `undefined`, exactly as it does for a single-turn preview — try-it-out's frame carries only the accumulated response columns, never `$_metrics`
