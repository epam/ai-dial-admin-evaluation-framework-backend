## Why

The `structured-query-model` spec defined the request-side object model and explicitly carried three
follow-ups as **Planned**: *Query validation and allowlist*, *SQL translation*, and *Response
envelope*. The exploratory work on `feat/query-dsl` has since implemented an executable path —
`POST /api/v1/queries/execute` runs a structured query against an entity and returns rows — so those
three requirements are no longer planned. This change documents the executor as-built and reconciles
the spec, including where the implementation is **narrower** than the originally-planned vision.

## What Changes

- Add the **query execution endpoint** `POST /api/v1/queries/execute` (request body: a structured
  query envelope; response: a `rows` array plus a nullable `totalCount`). Dispatch is entity-agnostic:
  `StructuredQueryService` routes by the query's `entity` to the matching `StructuredQueryRepository`,
  and an unknown entity is a validation error (400).
- Implement **validation** as **schema-driven and permissive** (not the originally-planned
  capability-flag allowlist): field names must resolve against the entity's discovered schema
  (flat columns or `data:`/`response:`/`metric:`/`metricInfo:` JSONB paths), functions must be in a
  closed supported set, `in` operands must be arrays of literals, and literals must parse to their
  declared `value_type` — all failures are 400. **Not enforced** (deferred): per-field capability
  flags (`filterable`/`projectable`/`groupable`/`aggregatable`/`sortable`), mode coherence, and array
  type-homogeneity.
- Implement **SQL translation** to parameterized jOOQ: flat properties expand to physical columns or
  JSONB navigation/casts (metric values always numeric), the §3 operators map to SQL (`co`/`nc` →
  case-insensitive LIKE, `in` → `IN`, `eq`/`ne` null → `IS [NOT] NULL`), and aggregate /
  `group_by` / `having` / `sort` resolve against base fields ∪ select aliases. Offset pagination only:
  default limit 100, max 1000; cursor pagination is rejected.
- Implement the **response** as `StructuredQueryResultDto { rows, totalCount }` — row maps with JSONB
  columns parsed back to nested JSON (`JsonbRowConverter`); `totalCount` is populated only for
  row-mode queries that opt in via `include_total`. The richer planned envelope (a `page` object with
  `offset`/`total`/`next_cursor`, and an aggregate `keys`+`metrics` row shape) is **not** implemented.
- **BREAKING** to the prior spec text only: the three Planned requirements are rewritten to match the
  implementation and re-marked Implemented; the spec Purpose no longer calls them future follow-ups.

## Capabilities

### New Capabilities
<!-- None. Execution is documented within the existing structured-query-model spec. -->

### Modified Capabilities
- `structured-query-model`: flips *Query validation and allowlist*, *SQL translation*, and *Response
  envelope* from Planned to Implemented (rewritten to the as-built, narrower behavior), and adds a new
  *Query execution endpoint* requirement covering `POST /execute`, dispatch, and unknown-entity
  rejection.

## Impact

- **Packages**: `experimental.query.web` (`StructuredQueryController`, `JsonbRowConverter`),
  `experimental.query.service` (`StructuredQueryService`), `…service.repository`
  (`StructuredQueryRepository` SPI, `StructuredQueryExecutor`, `QueryResultPage`, the two Postgres
  repositories), `…service.translate` (`StructuredQueryBuilder`, `FilterTranslator`, `ExprTranslator`,
  `JsonbFieldResolver`, `ValueExprToObjectMapper`), and `…service.dto.StructuredQueryResultDto`.
- **APIs**: one new `POST` endpoint under `/api/v1/queries`. No change to existing endpoints or the
  legacy list-query DSL.
- **Data**: no schema/migration change. Reads via the meta DSLContext (`test_suites`) and analytics
  DSLContext (`eval_summaries`); repositories are `@ConditionalOnProperty` on their datasource vendor.
- **Security**: unchanged; queries are body-delivered, never raw SQL, and translate to parameterized
  jOOQ.
- **Config**: none.
- **Docs**: updates `openspec/specs/structured-query-model/spec.md` (delta) and may flip its
  `openspec/specs/README.md` status from Partial toward Implemented at archive time.
