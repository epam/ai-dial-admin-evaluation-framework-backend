# AI Agent Context - Evaluation Framework Backend

## Quick Reference

| Aspect | Value |
|--------|-------|
| Language | Java 25 |
| Framework | Spring Boot 4.1.0 |
| Build | Gradle 9.6.0 |
| Database | PostgreSQL (JDBC only, NO JPA) |
| Security | OIDC/JWT multi-issuer |
| Testing | JUnit 5 + Testcontainers |
| Formatting | Spotless + palantir-java-format (`./gradlew spotlessApply`) |

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                       Web Layer (.web)                       │
│  ┌─────────────┐  ┌─────────────────────┐  ┌─────────────┐  │
│  │ Controllers │  │ Exception Handlers  │  │  Security   │  │
│  └──────┬──────┘  └─────────────────────┘  └─────────────┘  │
├─────────┼───────────────────────────────────────────────────┤
│         ▼              Service Layer (.service)              │
│  ┌────────────────────────────┐ ┌─────────────────────────┐ │
│  │     .service.domain        │ │ .service.infrastructure │ │
│  │  ┌──────────────────────┐  │ │  ┌───────────────────┐  │ │
│  │  │ Business Services    │  │ │  │ Health Indicators │  │ │
│  │  │ DTOs + Mappers       │  │ │  │ Logger Config     │  │ │
│  │  │ Exceptions           │  │ │  │ Transaction Aspect│  │ │
│  │  └──────────────────────┘  │ │  └───────────────────┘  │ │
│  │  (Transaction mgmt here)   │ │                         │ │
│  └────────────────────────────┘ └─────────────────────────┘ │
├─────────────────────────────────────────────────────────────┤
│                   Data Access Layer (.data.db)               │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  Repository Interfaces + Postgres Implementations   │    │
│  │  RecordMappers, Models, Pagination, Transaction Context│   │
│  └──────────────────────┬──────────────────────────────┘    │
├─────────────────────────┼───────────────────────────────────┤
│                         ▼                                    │
│  ┌─────────────────────────────────────────────────────┐    │
│  │              PostgreSQL + Flyway                     │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

## Do's and Don'ts

### DO ✅
- Use the typed jOOQ DSL via `@Qualifier("metaDsl"|"analyticsDsl") DSLContext` for database queries
- Use `*RecordMapper` `@Component`s for `Record → domain` mapping
- Use Lombok (`@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j`)
- Use MapStruct for DTO ↔ Model mapping
- Use established libraries (Apache Commons, Guava, Jackson, etc.) for common utilities and data formats (e.g. CSV) instead of custom implementations
- Use specialized, injectable components for conversion/validation logic instead of private/inner methods to facilitate reuse and testing
- Use `@Transactional("metaTransactionManager")` for meta DB services and `@Transactional("analyticsTransactionManager")` for analytics DB services; unqualified `@Transactional` is not safe in a dual-datasource setup
- Add OpenAPI annotations to controllers
- Write functional tests with `@PostgresFunctionalTests`
- Use `MetaTestDataHelper` / `AnalyticsTestDataHelper` (injected via `@Autowired`) for fixture creation and cleanup in functional tests; do not inject `JdbcTemplate` directly in test classes
- Use repositories for state assertions in functional tests (`repository.findById(id)`, `repository.count()`) instead of raw SELECT queries
- Name back-door test helpers after intent, not mechanism (e.g. `forceSuiteInvalid`, not `setIsValidFalse`)
- Use `Page<T>` and `PageRequest` for pagination
- Use `PaginationParamResolver` (inject in list controllers) to resolve and validate `page`/`size` query params with configurable defaults; do not duplicate resolvePage/resolveSize logic in controllers
- Use **pagination or batch/streaming** for bulk and export operations (e.g. CSV export/import, large list exports) instead of loading the full dataset into memory; respect configured page/batch size limits
- Store UUIDs as `VARCHAR(36)` in database
- Use `@Validated` on list controllers. For `filter` params, bind with `@FilterParam List<String> filter` (from `web.pagination`) — this preserves commas verbatim inside a single value and enforces the count cap via the annotation's `max`. For `sort` params keep `@RequestParam(name = "sort", required = false) @Size(max=32) List<String> sort`. Use `@Size(max=128)` on `TestCasesDefinitionDto.factFields`; CSV delimiter must be single ASCII character (validate in controller, return HTTP 400 when invalid).
- When a service builds `TestCaseRunResult`-like domain objects programmatically (e.g. from CSV parsing) rather than via a `@Valid @RequestBody` binding, validate required fields with explicit inline checks rather than `jakarta.validation.Validator` — inline checks avoid the indirection of a DTO intermediary and keep validation co-located with parsing. Use `jakarta.validation.Validator` only when a separate DTO class already carries `@NotNull`/`@Min`/`@Max`/`@Size` annotations whose reuse across multiple paths justifies the Bean Validation overhead.
- Expose JSONB-backed JSON schema fields (e.g. `config_schema`, `input_schema`, `output_schema`) as `Map<String, Object>` in response DTOs, not as raw `String`. The DB model keeps `String`; the mapper layer converts via `JsonbMapper.mapJsonSchema`. This ensures the REST API returns proper JSON objects to clients.
- All Spring component classes MUST have `@LogExecution` annotation at the class level (`@RestController`, `@Controller`, `@Service`, `@Repository`, `@Component`, `@Configuration`). Import: `com.epam.aidial.evaluation.configuration.logging.LogExecution`
- **In every `catch` block, the caught exception MUST be passed as the LAST SLF4J argument** — e.g. `log.warn("…: {}", id, e.getMessage(), e)`. Only the trailing `Throwable` vararg triggers stacktrace rendering; without it, ops sees only the formatted message and has no way to diagnose the failure. `e.getMessage()` is **not** a substitute — it is frequently `null` (NPE, many JDK exceptions) and never carries the cause chain. This rule applies to `log.error`, `log.warn`, `log.debug`, and `log.trace`. Bad: `log.warn("Failed: {}", e.getMessage())` → renders `Failed: null` with no stack. Good: `log.warn("Failed: {}", e.getMessage(), e)` → renders message + full stacktrace. Enforced by `LoggingConventionTest`.
- Catch specific exceptions, not generic `Exception`/`Throwable`.
- Use imports and short names; never use FQNs in method bodies, signatures, or annotation attributes (Checkstyle-enforced).
- Code formatting (indentation, line wrapping, parameter/argument layout, import ordering, whitespace) is owned by **Spotless + palantir-java-format** — do not hand-format. Run `./gradlew spotlessApply` before committing; `spotlessCheck` runs as part of `build` (CI). The formatter keeps parameter/argument lists fully horizontal when they fit and switches the whole list to one-per-line otherwise (never a mixed layout), so the previous manual rule is now enforced automatically. Generated sources under `src/main/java-generated/` are excluded.
- All `@ConfigurationProperties` defaults MUST be defined in `application.yml`, not as Java field initializers. Properties classes hold only structure, binding, and validation (`@Validated`, `@NotNull`, `@Min`, etc.).
- Non-configurable constants MUST be defined once in a constants class per bounded context (e.g., `ValidationConstants`, `SecurityConstants`). No duplicate definitions across classes.
- Use typed builders (`Jackson ObjectNode`/`ObjectMapper`, `UriComponentsBuilder`) for constructing JSON and structured data. Never use `StringBuilder` or `String` concatenation for JSON.
- All parameters for external system integration (URLs, IDs, org identifiers, credentials) MUST be configurable via `@ConfigurationProperties`. Never hardcode values specific to an external system's deployment.
- Never call `System.currentTimeMillis()`, `Instant.now()`, or `new Date()` in production code. Inject `java.time.Clock` (provided by `ClockConfiguration` bean) and use `clock.millis()` or `Instant.now(clock)`. In unit tests, use `Clock.fixed(instant, zone)` for deterministic assertions.
- Every `@Test` assertion MUST directly verify the behavior stated in the test's `@DisplayName`. Functional tests MUST have deterministic assertions — no if/else branching where only one branch executes per run.
- When changing API endpoints or DTOs, update OpenAPI examples (`@Schema example`, `@ExampleObject`) to reflect the new contract.
- When adding Flyway migrations that change schema, update `docs/database-schema.md`.
- When adding a configuration property: add a row to `docs/configuration.md` with all six columns (`Property | Environment Variable | Default | Required | Applied when | Description`) in the same PR; see [configuration-docs spec](openspec/specs/configuration-docs/spec.md) for the full rule (column schema, four-term `Required` vocabulary, top-level grouping).
- Prefer extracting separate methods with descriptive names to large enclosing methods with lots of commentary.
- Use final for local variables that do not change.

### DON'T ❌
- Don't use JPA/Hibernate - this is a JDBC-only project
- Don't use `@Entity` or `@Repository` JPA annotations
- Don't create `JpaRepository` interfaces
- Don't use field injection (`@Autowired` on fields)
- Don't ignore checkstyle warnings (run `checkstyleMain` and `checkstyleTest`)
- Don't hardcode configuration values
- Don't skip writing tests
- Don't place Spring MVC / web binding quirks (e.g., query param parsing) inside `.data.db` types like `PageRequest`.
- Don't hand-format code in ways the formatter will revert (manual indentation, custom line wrapping, import reordering). Run `./gradlew spotlessApply` and let palantir-java-format own layout.
- Don't inject `JdbcTemplate` or `NamedParameterJdbcTemplate` directly into functional test classes — use `MetaTestDataHelper` / `AnalyticsTestDataHelper` instead
- Don't write raw `INSERT`/`UPDATE`/`SELECT` SQL in functional test methods or `@BeforeEach` blocks — keep SQL inside helpers only
- Don't duplicate fixture creation logic (`createTestSuite`, `createTestSuiteRun`) across test classes — extract shared helpers to base classes or `*TestDataHelper` classes
- Don't add test-only methods to production repository interfaces unless the operation is a legitimate data concern owned by that repository
- Don't inject a foreign domain's repository into a service. A domain service may only depend on its own domain's repository; cross-domain access goes through that domain's service (e.g., `DatasetService` calls `testSuiteService.bindDataset(...)`, not `testSuiteRepository.save(...)`). If the owning service does not yet expose the needed method, add it there first. See [best-practices spec](openspec/specs/best-practices/spec.md).
- Don't serialize `Map<String, Object>` with Java `null` values using the shared `ObjectMapper` — the global `NON_NULL` serialization inclusion will silently drop null-valued entries, producing `{}` instead of `{"key":null}`. For JSONB fields that must preserve explicit JSON nulls (e.g. `extracted_columns`), use `ObjectNode` with `putNull(key)` for null entries and `node.set(key, objectMapper.convertValue(value, JsonNode.class))` for non-null values, then serialize the `ObjectNode`.
- Don't edit files under `src/main/java-generated/` — they are auto-generated by `./gradlew generateJooq` and will be overwritten on the next regeneration. To reflect a schema change, add a Flyway migration and run `./gradlew generateJooq`, then commit the diff.

### Best practices
Code-quality practices (imports over FQNs, config defaults in YAML only, constants per bounded context, no duplicated logic) are defined in [openspec/specs/best-practices/spec.md](openspec/specs/best-practices/spec.md). New code MUST follow that spec.

### OpenAPI examples
Add request/response examples to the OpenAPI spec. Use `@Schema(example = "…")` on DTO fields; for operation-level examples, add JSON files under `src/main/resources/openapi/examples/`. Non-trivial endpoints need minimal + full examples. Keep examples in sync when changing endpoints. See [openapi-examples spec](openspec/specs/openapi-examples/spec.md).

### OpenAPI query parameter docs
`OpenApiQueryParamCustomizer` auto-generates rich descriptions for `filter`/`sort`/`page`/`size`/`cursor` params from `FilterWhitelists`/`SortWhitelists`/`PaginationProperties`. **When adding a new list endpoint**, add a registry entry in the customizer. See [openapi-query-param-docs spec](openspec/specs/openapi-query-param-docs/spec.md).

## Code Templates

Full templates for adding new entities (model, RecordMapper, repository, service, controller, migration, test, DTOs, mapper) are in [docs/code-templates.md](docs/code-templates.md).

## Unique Patterns

Detailed pattern docs live in [docs/patterns/](docs/patterns/README.md). Substantial patterns are linked below; one-line conventions stay inline.

| Pattern | Why it matters |
|---------|----------------|
| [TokenPropagationHelper](docs/patterns/token-propagation.md) | Propagate auth token across `CompletableFuture.supplyAsync` and other pooled-thread boundaries |
| [TransactionTimestampContext + Aspect](docs/patterns/transaction-timestamp.md) | One shared `createdAt`/`updatedAt` per `@Transactional`; explicit-param repo signatures for `TransactionTemplate` callers |
| [Dual Datasource (Meta + Analytics)](docs/patterns/dual-datasource.md) | Required `@Qualifier`s for DSLContext / tx manager / conditionals — getting these wrong silently uses the wrong DB |
| [CursorCodec & Keyset Pagination](docs/patterns/cursor-pagination.md) | Analytics layer; `LIMIT size+1` pattern + opaque Base64 cursor |
| [SchemaTypeCoercer vs SchemaChangeCoercer](docs/patterns/schema-coercers.md) | Permissive (CSV import) vs strict (revalidation after schema-type change) — pick the right one |
| [DIAL Core File Storage](docs/patterns/dial-file-storage.md) | `DialFileClient` + `DialFileRefResolver`; suite-scoped `@ef/suites/{suiteId}/{filename}` and dataset-scoped `@ef/datasets/{datasetId}/{filename}` references |
| [RequestBodySerializerRegistry](docs/patterns/request-body-serializer-registry.md) | Strategy pattern for JSON / multipart / urlencoded request bodies |
| [JSONB_NUMERIC Multi-Level Path Filtering](docs/patterns/jsonb-numeric-filtering.md) | Two-level JSONB filtering with parameterized path components |
| [Typed SQL DSL (jOOQ)](docs/patterns/jooq-typed-sql-dsl.md) | Codegen pipeline, drift guard, DSLContext config, RecordMapper convention |
| [Dataset Entity](docs/patterns/dataset-entity.md) | `DatasetSchemaProvider`, `disabledTestCaseIds`, `dataset.id` vs `suite.id`, visibility rules |
| [Suite Run Snapshot Phase](docs/patterns/suite-run-snapshot.md) | Snapshot tx, `40001` retry, inconsistent-snapshot guard, version handling |
| [Selective Column Projection (TOAST)](docs/patterns/selective-column-projection.md) | Column-tier constants to avoid TOAST decompression on bulk queries |
| [MCP Tool Invocation](docs/patterns/mcp-tool-invocation.md) | Per-call `McpSyncClient` via DIAL Core MCP proxy |

### Inline conventions

- **Bulk and export operations** — use paginated DB queries with streaming response, or batched parsing/persistence, for bulk/export (e.g. CSV). Never load full datasets into memory; respect pagination max size.
- **AuthorResolver** (`service.domain.AuthorResolver`) — extracts user identity from JWT for `createdBy` fields. Uses configurable claim name (`security.jwt.user-claim`), not hardcoded `sub`. Returns `"anonymous"` when JWT is null (security mode `none`).
- **API Timestamp Convention** — all timestamps in REST APIs and DB models use **epoch milliseconds (Long)**. Do NOT convert to `Instant` or ISO 8601 strings in DTOs; MapStruct maps `Long → Long` automatically.
- **ValidationWarningsSerializer** — injectable `@Component` for JSON ser/deser of validation warnings and maps. **Fail-fast** (throws) for serialization; **graceful degradation** (logs + empty) for deserialization. Inject instead of duplicating `ObjectMapper` logic.
- **Suite validity = config only** — `isValid` reflects configuration correctness (template + bindings + endpoint schema) only. Test-case presence is **not** a component of stored suite validity and does not affect `isValid` or `validationWarnings` in the suite GET response. `SuiteValidationService.validateSuite(...)` is config-only; calling it on test-case mutations is not needed and must not be done. Test-case presence is enforced at **run-creation time** only: `TestSuiteRunService.createRun` guard #4 counts runnable test cases via `RunnableTestCaseCounter.countRunnable(datasetId, filterJson, disabledIds)` and throws `InvalidOperationException("Suite has no valid and enabled test cases")` (→ 409 `INVALID_OPERATION`) when count is zero. Guard order: 1.not-found 2.unbound 3.config-invalid 4.zero-runnable 5.rate-limits. Test-case **data** validation (`test_cases.is_valid`) is owned by the test-case domain + Phase 1 and is **never** triggered from suite validation.
- **Computation Versioning (No `is_latest` flag)** — analytics entities versioned by `computation_id` (UUID) use append-only writes. "Latest" is resolved at query time (`ORDER BY computed_at_ms DESC LIMIT 1`); API callers pass `computation=<uuid>` or `computation=latest` (or omit).
- **Exception Handling Pattern** — **fail-fast (throw)** for data integrity (serialization, writes); **graceful degradation (log + fallback)** only when data is regenerable. Document rationale in comment or log message. See also `config.yaml` global rules.
- **Query DSL `ParamExpr`** — `param` expressions ARE supported (do not re-reject them), resolved by a **single pre-pass** (`QueryParameterResolver` in `experimental.query.service.translate`) that rewrites a `StructuredQuery` into a parameter-free copy *before* translation — substituting each `ParamExpr` with its bound `Expr` recursively (unbound → 400; param-to-param → 400; cyclic chain → 400). The resolver is invoked once at the `StructuredQueryService.execute(query, params)` entry; the translator/builder/executor/resolver are **parameter-agnostic** (do NOT re-introduce a `Map<String,Expr> params` argument threaded through them — that was the prior shape and was deliberately removed). `ExprTranslator` keeps a defensive `case ParamExpr → 400` since a surviving param means it was unbound. Internal callers use `StructuredQueryService.execute(query, params)`; the public `POST /api/v1/queries/execute` stays **paramless** by design (empty map → resolver is a no-op → any `param` is rejected by the translator guard → 400). The `metric-score-statistics` Phase-3 computation uses this param path (`StructuredQueryService.execute(query, params)`) to run the built-in statistic queries. Those queries (AVG/P10/P90/MIN/MAX and the default `overall`) are **code-defined** as typed `StructuredQuery` objects in `BuiltInMetricStatistics`. Because the executor depends on `experimental.query.service`, it **lives in** `experimental.query.service.metricscore` (implementing the `MetricScoreComputation` interface declared in the stable `service.domain.job` layer); `TestSuiteEvaluationJob` triggers Phase 3 through that interface, so there is **no** `service → experimental.query.service` bytecode edge and `LayeredArchitectureTest` stays unmodified. When wiring a stable-layer trigger to experimental code, invert via a `service`-layer interface — do not relax the layering test.
- **Query DSL function catalog is registry-driven** — DSL functions are NOT a hardcoded switch. Each is a `QueryFunction` bean (SPI in `experimental.query.service.translate.function`) collected by `QueryFunctionRegistry`; `ExprTranslator` delegates to the registry. To add a function, drop in a new `@Component QueryFunction` (or a `@Bean` in `BuiltInQueryFunctions`) — no translator/registry edits. Built-ins live in `BuiltInQueryFunctions`. Duplicate names are rejected at startup; unknown names → `ValidationException` (400). Most built-ins wrap a jOOQ built-in directly, but a function's `Field<?>` result can also delegate to a **custom Postgres stored function** for computations `FunctionContext`'s single-`Field`-per-call contract can't express as pure jOOQ (e.g. multi-row ranking): `roc_auc(label, probability)` aggregates both columns via `DSL.arrayAgg(...)` and calls the stored function `roc_auc_score(double precision[], double precision[])` (analytics DB, `V1.11__CreateRocAucScoreFunction.sql`) via `DSL.function(...)` — usable anywhere a `FnExpr` is valid, with no changes to `StructuredQueryBuilder`/`StructuredQueryExecutor`. Arithmetic (`add`/`multiply`: n-ary, ≥1 arg, left-folded; `subtract`/`divide`: binary only, exactly 2 args) are further `BigDecimal`-cast `Field` arithmetic built-ins in the same catalog. There is deliberately **no** `mean`/`weighted_mean` DSL function — a suite's `overallScore` mean/weighted-mean composition (`divide(add(coalesce(avg(f1), 0), coalesce(avg(f2), 0), ...), n)` / `divide(add(multiply(w1, coalesce(avg(m1), 0)), ...), add(w1, ...))`) is built server-side by `OverallScoreDefinitionResolver` from these primitives, using the general-purpose `coalesce(value, default)` built-in (`DSL.coalesce`) to turn a missing metric's `NULL` average into `0` for that term (see the `OverallScoreDefinition` bullet below), never expressed as DSL JSON by a caller.
- **Typed `OverallScoreDefinition` for suite `overallScore`** — a suite's run-level `overall` metric-score definition (`TestSuiteRequestDto`/`TestSuiteResponseDto`/`SuiteSnapshotDto`.`overallScore`) is a sealed, JSON-discriminated model in `service.domain.dto.overallscore` (`Mean` — no params; `WeightedMean` — a `List<WeightedMetric>` of `{metricName, outputField, weight}`; `CustomFunction` — the prior free-form raw `StructuredQuery` expression Map, unchanged escape hatch), not a raw `Map<String, Object>`. `experimental.query.service.metricscore.OverallScoreDefinitionResolver` (a plain same-package collaborator of `MetricScoreComputationExecutor`, no interface inversion needed) turns the typed definition into a `StructuredQuery` at Phase-3 computation time: `Mean` resolves against the run's **currently discovered** numeric metric fields (not anything persisted on the definition); `WeightedMean` composes directly from its stored list (not cross-validated against the suite's configured TSMDs at write time — permissive; a missing metric's `avg` resolves to SQL `NULL` but is coalesced to `0` for that term via the `coalesce` DSL function, so it does not null the whole `overall` result); `CustomFunction` converts its Map via `objectMapper.convertValue(..., StructuredQuery.class)` (catch `JacksonException`, not `IllegalArgumentException`, on malformed input — log + skip). `MetricScoreComputationContext.overallScoreDefinition` carries the typed value directly (no JSON-string round trip between the suite snapshot and Phase 3).
- **Query DSL entity resolution** — `experimental.query.service.repository.StructuredQueryEntityResolver` is the single SPI every queryable entity implements: `entity()`, `dsl()`, `table()`, `bindings(StructuredQuery)`, `default rewrite(StructuredQuery)` (identity). `StructuredQueryEntityRegistry` collects all resolver beans at startup into a `Map<String, resolver>` (one per entity, gated by `@ConditionalOnProperty` on datasource vendor); `require(entity)` is the single unknown-entity 400 check, used by `StructuredQueryBuilder`/`StructuredQueryExecutor`/`StructuredQueryService` alike. `StructuredQueryBuilder.build`/`countRows` take only a `StructuredQuery` and resolve `dsl`/`table`/`bindings` from `entityRegistry.require(query.entity())` — no caller passes them in. `test_suites`/`eval_summaries`/`metric_score_results` compute their (static, per-table) bindings once in their resolver's constructor; `metric_score_results`' resolver overrides `rewrite` to delegate to the unchanged `MetricScoreLatestComputationDefaulter`. There is no per-`Table` bindings cache anymore — each non-instance-aware resolver's bindings are just a plain field.
- **Query DSL subquery-valued `in`, and anywhere else an expression is valid** — the `in` predicate's right operand may be an `array` of literals **or** a `subquery` (`SubqueryExpr`, wire `{"type":"subquery","query":{…}}`); a `subquery` may also appear as any other comparison's operand, a `select` projection, or a function argument. `StructuredQueryBuilder.compileSubqueryMembership(SubqueryExpr)` builds and wraps the nested query (`build(inner)` — a plain self-call, resolving `inner`'s own entity via the registry — then a derived-table wrap selecting the **first** column as the membership key, e.g. `left IN (SELECT firstCol FROM (<subquery>) …)`; extra columns may drive the inner query's own `ORDER BY`/`LIMIT`, e.g. `max(computed_at_ms)`). `FilterTranslator`'s `in` handling and `ExprTranslator.toField`'s `SubqueryExpr` case (which wraps the same compiled select as a scalar `Field` via `DSL.field(...)`) both reach it through **`ExprTranslator`'s lazy `ObjectProvider<StructuredQueryBuilder>`** — the only lazy-bean reference in the pipeline, breaking the `StructuredQueryBuilder → FilterTranslator/ExprTranslator` constructor cycle. `FilterTranslator` itself has **no dependency on the builder** and no signature change from this — it just calls `exprTranslator.compileSubqueryMembership(subquery)`. No same-entity check: a subquery may target any registered entity; if it lives on a different datasource than the enclosing query, the nested SQL fails at the database with a normal grammar error, mapped to 400 like any other DB-level type/grammar mismatch — not a structural validation rule. `QueryParameterResolver` recurses into `SubqueryExpr`.
- **`test_cases` query entity + suite `testCaseFilter`** — `test_cases` is a **complex** queryable entity keyed by `dataset_id` (`TestCasesSchemaProvider`); its flattened `data::<field>` typing is **dataset-specific**, so its `PostgresTestCaseEntityResolver.bindings(query)` is **instance-aware**: it requires a top-level `dataset_id` equality filter (missing/non-UUID → 400) and builds typed bindings via `TestCaseFieldBindingsBuilder` — this is simply that entity's own implementation of the same `bindings(StructuredQuery)` method every resolver implements, not a separate bypass-the-cache code path (cache-backed `test_suites`/`eval_summaries`/`metric_score_results` just ignore the query and return a precomputed map). `co`/`nc` on an `ARRAY`-typed `data::<field>` translate to JSONB containment (§ Query DSL function catalog). A suite carries an optional `testCaseFilter` (JSONB, mirrors `overallScore`) **validated at write time** (`TestSuiteService` → 400 on unknown field / unbound suite). It is applied at run time — combined `is_valid AND NOT excluded AND filter` — via the inverted **`service.domain.job.RunnableTestCaseSelector`** interface (impl `QueryDslRunnableTestCaseSelector` in `experimental.query.service`, same inversion as `MetricScoreComputation`); `RunnableTestCaseCounter` (guard #4) and `TestSuiteEvaluationJob.attemptSnapshot` call it, so there is **no** `service → experimental` edge.

## Key Packages Reference

| Package                               | Pu†rpose                                                                                                                                                                                                                                                            |
|---------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `com.epam.aidial.evaluation`          | Root package                                                                                                                                                                                                                                                        |
| `.client.dialcore`                    | DIAL Core client (deployments, models, deployment invocation, file operations)                                                                                                                                                                                      |
| `.client.mcp`                         | MCP SDK client (tool invocation, tool listing via DIAL Core MCP proxy)                                                                                                                                                                                              |
| `.configuration`                      | Spring @Configuration classes                                                                                                                                                                                                                                       |
| `.configuration.datasource`           | DataSource setup                                                                                                                                                                                                                                                    |
| `.configuration.jackson`              | Jackson ObjectMapper customizations                                                                                                                                                                                                                                 |
| `.configuration.logging`              | Logging, correlation IDs                                                                                                                                                                                                                                            |
| `.configuration.properties`           | ConfigurationProperties classes                                                                                                                                                                                                                                     |
| `.configuration.properties.analytics` | Analytics-specific config (EvalSummaryProperties, AnalyticsResultsProperties)                                                                                                                                                                                       |
| `.configuration.properties.dial`      | DIAL-specific config (DialProperties, DialCoreProperties, DialFileStorageProperties)                                                                                                                                                                                |
| `.configuration.security`             | Security & OIDC configurations                                                                                                                                                                                                                                      |
| `.constants`                          | Constants (Security, TestSuiteRun, Validation)                                                                                                                                                                                                                      |
| `.data.db.exception`                  | DB-specific exceptions                                                                                                                                                                                                                                              |
| `.data.db.jooq.meta`                  | Generated jOOQ classes for meta schema (Tables, Records, Keys, Indexes)                                                                                                                                                                                             |
| `.data.db.jooq.analytics`             | Generated jOOQ classes for analytics schema (Tables, Records, Keys, Indexes)                                                                                                                                                                                        |
| `.data.db.mapper`                     | RecordMapper `@Component`s for meta entities (maps jOOQ Record → domain model)                                                                                                                                                                                      |
| `.data.db.model`                      | Meta domain models                                                                                                                                                                                                                                                  |
| `.data.db.model.filter`               | Filter models                                                                                                                                                                                                                                                       |
| `.data.db.model.pagination`           | Page, PageRequest, SortKey                                                                                                                                                                                                                                          |
| `.data.db.repository`                 | Meta data access with jOOQ DSLContext                                                                                                                                                                                                                               |
| `.data.db.repository.sql`             | SQL builders (WhereBuilder, OrderByBuilder, PageRequestSqlBuilder, FilterWhitelists, SortWhitelists)                                                                                                                                                                |
| `.data.db.repository.sql.json`        | JsonPathAccessor — JSONB path access abstraction (`->`, `->>`, numeric cast)                                                                                                                                                                                        |
| `.data.db.transaction.timestamp`      | Transaction timestamp context (meta only)                                                                                                                                                                                                                           |
| `.data.db.analytics.mapper`           | RecordMapper `@Component`s for analytics entities                                                                                                                                                                                                                   |
| `.data.db.analytics.model`            | Analytics domain models (TestCaseRunResult, EvalSummary, RunMetricSnapshot, ExecutionStatus)                                                                                                                                                                        |
| `.data.db.analytics.model.cursor`     | Cursor, CursorPage — keyset pagination carriers                                                                                                                                                                                                                     |
| `.data.db.analytics.repository`       | Analytics data access with jOOQ DSLContext (append-only, batch writes)                                                                                                                                                                                              |
| `.experimental.query.model`           | **(experimental)** Structured query DSL request model — sealed `Expr`/`FilterNode`/`PageSpec`, `StructuredQuery`, enums, `FilterNodeDeserializer`                                                                                                                   |
| `.experimental.query.service`         | **(experimental)** Schema discovery (`QueryableEntitySchemaProvider` SPI + `QueryEntityRegistry`, `JooqTableSchemaResolver`, per-entity providers) and entity-agnostic query dispatch/execution/translation (`StructuredQueryService`, `…repository`, `…translate`) |
| `.experimental.query.service.metricscore` | Phase-3 metric-score computation: `BuiltInMetricStatistics`, `MetricScoreComputationExecutor` (implements `service.domain.job.MetricScoreComputation`), `OverallScoreDefinitionResolver` (typed `OverallScoreDefinition` → `StructuredQuery`)                    |
| `.experimental.query.service.translate.function` | Registry-driven DSL function catalog: `QueryFunction` SPI, `QueryFunctionRegistry`, `FunctionContext`, `BuiltInQueryFunctions`                                                                                                        |
| `.experimental.query.web`             | **(experimental)** Structured query controllers under `/api/v1/queries` (schema discovery + `POST /execute`)                                                                                                                                                        |
| `.service.domain`                     | Business logic services                                                                                                                                                                                                                                             |
| `.service.domain.analytics`           | Analytics services, CursorCodec                                                                                                                                                                                                                                     |
| `.service.domain.csv`                 | CSV processing utilities                                                                                                                                                                                                                                            |
| `.service.domain.dto`                 | DTOs with validation (meta entities)                                                                                                                                                                                                                                |
| `.service.domain.dto.analytics`       | Analytics DTOs (BatchWriteRequestDto, CursorPageResponseDto, etc.)                                                                                                                                                                                                  |
| `.service.domain.dto.overallscore`    | Typed suite `overallScore` model: sealed `OverallScoreDefinition` (`Mean`, `WeightedMean`, `WeightedMetric`, `CustomFunction`), JSON-discriminated by `type`                                                                                                       |
| `.service.domain.exception`           | Custom exceptions                                                                                                                                                                                                                                                   |
| `.service.domain.filter`              | Filter parsing & execution                                                                                                                                                                                                                                          |
| `.service.domain.job`                 | Job execution models; SSE parsing (`SseEventParser`, `SseEvent`, `SseParseResult`)                                                                                                                                                                                  |
| `.service.domain.mapper`              | MapStruct mappers                                                                                                                                                                                                                                                   |
| `.service.domain.sort`                | Sort parsing & execution                                                                                                                                                                                                                                            |
| `.service.infrastructure.health`      | Actuator health indicators                                                                                                                                                                                                                                          |
| `.service.infrastructure.logger`      | Dynamic log level config                                                                                                                                                                                                                                            |
| `.service.infrastructure.transaction` | Transaction aspects                                                                                                                                                                                                                                                 |
| `.utils`                              | Utilities                                                                                                                                                                                                                                                           |
| `.web.controller`                     | REST controllers                                                                                                                                                                                                                                                    |
| `.web.handler`                        | Global exception handler                                                                                                                                                                                                                                            |
| `.web.pagination`                     | PaginationParamResolver (page/size resolution), `@FilterParam` + FilterParamArgumentResolver (repeatable `filter` query param binding without comma-splitting)                                                                                                      |
| `.web.security`                       | JWT/OIDC security                                                                                                                                                                                                                                                   |

## Debugging Tips

1. **Check security mode**: Set `config.rest.security.mode=none` for local testing
2. **Enable SQL logging**: Set `logging.level.org.jooq.impl=DEBUG` (jOOQ) or `logging.level.org.springframework.jdbc=DEBUG` (Spring JDBC, health indicators)
3. **Regenerate jOOQ sources**: Run `./gradlew generateJooq` after schema changes; commit the result
4. **Check Flyway**: Meta migrations in `resources/db/migration/meta/POSTGRES/`; analytics migrations in `resources/db/migration/analytics/POSTGRES/`
4. **Correlation ID**: Look for `X-Correlation-Id` header in requests/responses
5. **Swagger UI**: Available at `http://localhost:8080/swagger-ui.html`

## Design Documentation

For deeper understanding of the system architecture and design decisions:

| Document | Purpose |
|----------|---------|
| [Entity-Relationship Model](docs/design/entity-relationship-model.md) | Data model design, entity catalog, technology decisions |
| [Infrastructure Architecture](docs/design/infrastructure-architecture.md) | Component architecture, deployment model, data flows |
| [Database Schema Reference](docs/database-schema.md) | Current database tables, columns, indexes, JSONB schemas |
| [Configuration Reference](docs/configuration.md) | Application configuration options |

## AGENTS.md Maintenance

This file is the **quick-start reference for AI agents and developers**. It must stay accurate but concise.

**MUST contain** (update when changed):
- Quick Reference table (versions, tech stack)
- Architecture overview and layering rules
- Do's / Don'ts that are project-specific and non-obvious
- Code templates in [docs/code-templates.md](docs/code-templates.md) (model → mapper → repo → service → controller → migration → test)
- Unique patterns not discoverable from a single file — substantial ones live as their own doc under [docs/patterns/](docs/patterns/README.md) linked from the Unique Patterns table; one-line conventions stay inline in the "Inline conventions" subsection
- Key Packages Reference table
- Pointers to detailed docs and specs

**MUST NOT contain** (keep out):
- Generic Java/Spring best practices obvious to any developer
- Exhaustive file listings or every class name — agents can Glob/Grep for that
- Full API endpoint catalog — Swagger UI covers that
- Individual dependency versions (except top-level: Java, Spring Boot, Gradle)
- Session-specific or in-progress work details

**When to update**: After archiving an OpenSpec change, check if the change introduced new unique patterns, modified existing code templates, added packages, or bumped versions. If so — update the relevant section. If the change only added features following existing patterns — no update needed.

## Agent Workflow Rules

### Implementation Scope
- **Iterative Approach**: Do NOT implement the entire plan at once.
- **Batch Size**: Implement **max 5 tasks** and **max 1 task group** (one section from `tasks.md`) per iteration.
- **Exception**: Limits can be bypassed only if the user explicitly requests to "do it all at once".

### Post-Implementation Checks
- Check whether the change alters project-wide conventions, architecture, or tooling per the Config Maintenance Policy in `openspec/config.yaml` context. If yes, update `config.yaml` as part of the same change. If the change only adds features following existing patterns, do NOT update `config.yaml` — update the relevant spec instead.
- Check whether `openspec/specs/README.md` needs updating per the Spec Index Maintenance Policy in `openspec/config.yaml` context (new spec folder, status change, or materially inaccurate summary). Update as part of the same change.

### Test Execution Discipline
A task is NOT complete until its tests have actually been executed and pass. "Wrote a test" ≠ "verified". Before marking a task `[x]`:
- Run the newly added unit tests: `./gradlew test --tests "<fully.qualified.TestClass>"`.
- If the change touches an end-to-end path (controller → service → repository, or wire-up of a new Spring bean / AOP interaction), run the corresponding functional test suite — e.g. `./gradlew test --tests "com.epam.aidial.evaluation.functional.PostgresFunctionalTests\$<NestedTests>"`.
- Static review alone (reading files, grep for symbols) does NOT substitute for execution. `/opsx:verify` is a static check; it confirms *artifacts exist*, not that *the app boots and tests pass*.
- When a change introduces a new Spring bean with constructor injection, adds `@Qualifier`, wires into an aspect, or uses `TransactionTemplate` programmatically, run at least one functional test that boots the application context. A compile-clean build does NOT guarantee context startup.

