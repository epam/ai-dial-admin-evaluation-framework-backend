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
| [Dataset Entity](dataset-entity.md) | `DatasetSchemaProvider`, `disabledTestCaseIds`, `dataset.id` vs `suite.id`, visibility rules |
| [Suite Run Snapshot Phase](suite-run-snapshot.md) | Snapshot tx, `40001` retry, inconsistent-snapshot guard, version handling |
| [Selective Column Projection (TOAST)](selective-column-projection.md) | Column-tier constants to avoid TOAST decompression on bulk queries |
| [MCP Tool Invocation](mcp-tool-invocation.md) | Per-call `McpSyncClient` via DIAL Core MCP proxy |
| [Multi-Request Chain](request-chain.md) | Chain normalization, chain-wide response-column namespace, accumulating-map `responseField` resolution, fail-fast, condition-based metric targeting |
