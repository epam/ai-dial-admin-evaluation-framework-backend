# Unique Patterns

Project-specific patterns not discoverable from a single file. Linked from [AGENTS.md](../../AGENTS.md).

| Pattern | Summary |
|---------|---------|
| [TokenPropagationHelper](token-propagation.md) | Propagate auth token to async/pooled threads (`CompletableFuture.supplyAsync`) |
| [TransactionTimestampContext + Aspect](transaction-timestamp.md) | Consistent `createdAt`/`updatedAt` across one `@Transactional` call; explicit-param repo signatures |
| [Dual Datasource (Meta + Analytics)](dual-datasource.md) | Required `@Qualifier`s for DSLContext / tx manager / conditionals on both datasources |
| [CursorCodec & Keyset Pagination](cursor-pagination.md) | Analytics layer; `LIMIT size+1` pattern, opaque Base64 cursor encoding |
| [SchemaTypeCoercer vs SchemaChangeCoercer](schema-coercers.md) | Permissive (CSV import) vs strict (schema-change revalidation) coercion rules |
| [DIAL Core File Storage](dial-file-storage.md) | `DialFileClient` + `DialFileRefResolver`; `@ef/suites/{suiteId}/{filename}` references |
| [RequestBodySerializerRegistry](request-body-serializer-registry.md) | Strategy pattern for JSON / multipart / urlencoded body serialization |
| [JSONB_NUMERIC Multi-Level Path Filtering](jsonb-numeric-filtering.md) | Two-level JSONB filtering with parameterized path components |
| [Typed SQL DSL (jOOQ)](jooq-typed-sql-dsl.md) | Codegen pipeline, drift guard, DSLContext config, RecordMapper pattern |
| [Dataset Entity](dataset-entity.md) | `DatasetSchemaProvider`, `dataset.id` vs `suite.id`, visibility rules, exclusion via `testCaseFilter` |
| [Suite Run Snapshot Phase](suite-run-snapshot.md) | Snapshot tx, `40001` retry, inconsistent-snapshot guard, version handling |
| [Selective Column Projection (TOAST)](selective-column-projection.md) | Column-tier constants to avoid TOAST decompression on bulk queries |
| [MCP Tool Invocation](mcp-tool-invocation.md) | Per-call `McpSyncClient` via DIAL Core MCP proxy |
| [Slash-containing path values](slash-path-values.md) | `/**` mapping + `WildcardPathResolver`; decode exactly once, never `URLDecoder` |
| [Suite validity = config only](suite-validity-and-run-guards.md) | `isValid` excludes test-case presence; the 5 ordered `createRun` guards |
| [Computation Versioning (no `is_latest`)](computation-versioning.md) | "Latest" resolved at query time from eval summaries, not snapshots |
| [Eval summaries = single read surface](eval-summaries-read-surface.md) | One summary per result row even at zero TSMDs; empty list ≠ "no metrics" |
| [Query DSL `ParamExpr`](query-dsl-parameters.md) | Single pre-pass resolver; invert stable→experimental via a `service` interface |
| [Query DSL function catalog](query-dsl-function-catalog.md) | Registry-driven `QueryFunction` SPI; stored-function delegation; no `mean` fn |
| [Typed `OverallScoreDefinition`](overall-score-definition.md) | Sealed `Mean`/`WeightedMean`/`CustomFunction`; `coalesce` keeps `overall` non-null |
| [Query DSL entity resolution](query-dsl-entity-resolution.md) | `StructuredQueryEntityResolver` SPI + registry as the single 400 check |
| [Query DSL subqueries](query-dsl-subqueries.md) | Subquery-valued `in` and scalar subqueries; the one lazy-bean cycle break |
| [Query DSL null polarity](query-dsl-null-polarity.md) | `nc`/`ne`/`not` are total (null satisfies); positive ops stay unwrapped/sargable |
| [`test_cases` query entity + `testCaseFilter`](test-cases-query-entity.md) | Instance-aware bindings keyed by `dataset_id`; scope-aware ALL-turns-match |
| [Multi-turn test cases](multi-turn-test-cases.md) | Emergent from `multi_turn_data`, not a suite flag; `perTurn` scope; turn loop |
| [Multi-request suites](multi-request-suites.md) | `additionalRequests` chain; one flat response-column union; accumulated frame; `(request_index, turn_index)` |
| [Request-template JSONata seam](jsonata-evaluation-seam.md) | `content` vs `jsonataContent`; `$_request`/`$_response`; never `.` in a binding name |
| [`evaluation-runner-core` module](evaluation-runner-core-module.md) | DB-free Phase 1 engine; autoconfiguration wiring; deliberate DTO duplication |
