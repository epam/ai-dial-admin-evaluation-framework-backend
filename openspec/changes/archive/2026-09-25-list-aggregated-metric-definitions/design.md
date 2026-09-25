## Context

See `proposal.md`. The repository already returns all aggregated definitions for a suite through the same declaration/version join used by the detail endpoint.

## Goals / Non-Goals

**Goals:**
- Expose the existing aggregate read through the service and REST layers.
- Keep the list response consistent with the existing aggregate DTO.

**Non-Goals:**
- Pagination, filtering, sorting, ordering guarantees, DTO expansion, repository-query changes, and parent-suite existence validation.

## Decisions

- Add a DTO-facing service method that maps `findAllAggregatedByTestSuiteId` results with the existing aggregate mapper. Keep the current domain-model method for internal callers.
- Return a raw array. The existing query has no ordering contract, and the endpoint does not need a page envelope.
- Return `[]` for no rows, including an unknown suite ID, matching existing list behavior.
- Use a static `/aggregated` mapping and OpenAPI array metadata/examples. Spring resolves it ahead of `/{id}`; a controller test will pin that behavior.

## Risks / Trade-offs

- [Unpaginated response] -> Suite metric-definition lists are expected to remain bounded; pagination is deferred rather than partially duplicating the standard list contract.
- [Shared query has unspecified ordering] -> Tests assert contents independently of order; no internal-consumer behavior changes.