# AI Agent Context - Evaluation Framework Backend

## Quick Reference

| Aspect | Value |
|--------|-------|
| Language | Java 25 |
| Framework | Spring Boot 4.1.0 |
| Build | Gradle 9.6.1 |
| Database | PostgreSQL (JDBC only, NO JPA) |
| Security | OIDC/JWT multi-issuer |
| Testing | JUnit 5 + Testcontainers |
| Formatting | Spotless + palantir-java-format (`./gradlew spotlessApply`) |
| Build layout | Multi-module: root app + `evaluation-runner-core` subproject (DB-free Phase 1 execution engine library, `com.epam.aidial.evaluation.runner`, contributed via Spring Boot autoconfiguration; test with `./gradlew :evaluation-runner-core:test`) + `eval-cli` subproject (standalone Spring Boot picocli CLI, `com.epam.aidial.evaluation.cli`, DB-free consumer of `evaluation-runner-core`; produces executable `bootJar`; test with `./gradlew :eval-cli:test`) |

## Architecture Overview

Strict layering, enforced by `LayeredArchitectureTest`. Dependencies point downward only:

`.web` (controllers, exception handlers, security)
→ `.service` (`.service.domain` business logic + DTOs + mappers + **all transaction management**; `.service.infrastructure` health / logging / transaction aspects)
→ `.data.db` (repository interfaces + Postgres impls, RecordMappers, models, pagination, transaction context)
→ PostgreSQL + Flyway.

Never invert an edge to reach experimental code — declare an interface in `.service` and implement it in the experimental package (see [Query DSL `ParamExpr`](docs/patterns/query-dsl-parameters.md)). Package inventory: [docs/key-packages.md](docs/key-packages.md).

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
| [Slash-containing path values](docs/patterns/slash-path-values.md) | `/**` mapping + `WildcardPathResolver`; decode exactly once, never `URLDecoder` |
| [Suite validity = config only](docs/patterns/suite-validity-and-run-guards.md) | `isValid` excludes test-case presence; the 5 ordered `createRun` guards |
| [Computation Versioning (no `is_latest`)](docs/patterns/computation-versioning.md) | "Latest" resolved at query time from eval summaries, not snapshots |
| [Eval summaries = single read surface](docs/patterns/eval-summaries-read-surface.md) | One summary per result row even at zero TSMDs; empty list ≠ "no metrics" |
| [Query DSL `ParamExpr`](docs/patterns/query-dsl-parameters.md) | Single pre-pass resolver; invert stable→experimental via a `service` interface |
| [Query DSL function catalog](docs/patterns/query-dsl-function-catalog.md) | Registry-driven `QueryFunction` SPI; stored-function delegation; no `mean` fn |
| [Typed `OverallScoreDefinition`](docs/patterns/overall-score-definition.md) | Sealed `Mean`/`WeightedMean`/`CustomFunction`; `coalesce` keeps `overall` non-null |
| [Query DSL entity resolution](docs/patterns/query-dsl-entity-resolution.md) | `StructuredQueryEntityResolver` SPI + registry as the single 400 check |
| [Query DSL subqueries](docs/patterns/query-dsl-subqueries.md) | Subquery-valued `in` and scalar subqueries; the one lazy-bean cycle break |
| [`test_cases` query entity + `testCaseFilter`](docs/patterns/test-cases-query-entity.md) | Instance-aware bindings keyed by `dataset_id`; scope-aware ALL-turns-match |
| [Multi-turn test cases](docs/patterns/multi-turn-test-cases.md) | Emergent from `multi_turn_data`, not a suite flag; `perTurn` scope; turn loop |
| [Request-template JSONata seam](docs/patterns/jsonata-evaluation-seam.md) | `content` vs `jsonataContent`; `$_request`/`$_response`; never `.` in a binding name |
| [`evaluation-runner-core` module](docs/patterns/evaluation-runner-core-module.md) | DB-free Phase 1 engine; autoconfiguration wiring; deliberate DTO duplication |

### Inline conventions

- **Bulk and export operations** — use paginated DB queries with streaming response, or batched parsing/persistence, for bulk/export (e.g. CSV). Never load full datasets into memory; respect pagination max size.
- **AuthorResolver** (`service.domain.AuthorResolver`) — extracts user identity from JWT for `createdBy` fields. Uses configurable claim name (`security.jwt.user-claim`), not hardcoded `sub`. Returns `"anonymous"` when JWT is null (security mode `none`).
- **API Timestamp Convention** — all timestamps in REST APIs and DB models use **epoch milliseconds (Long)**. Do NOT convert to `Instant` or ISO 8601 strings in DTOs; MapStruct maps `Long → Long` automatically.
- **ValidationWarningsSerializer** — injectable `@Component` for JSON ser/deser of validation warnings and maps. **Fail-fast** (throws) for serialization; **graceful degradation** (logs + empty) for deserialization. Inject instead of duplicating `ObjectMapper` logic.
- **Exception Handling Pattern** — **fail-fast (throw)** for data integrity (serialization, writes); **graceful degradation (log + fallback)** only when data is regenerable. Document rationale in comment or log message. See also `config.yaml` global rules.
- **Conditional metric execution (`condition` on TSMD)** — a TSMD's optional `condition` (JSONata, nullable ⇒ always run) decides per **result row (per turn)** whether the metric runs. `ConditionExpressionEvaluator` evaluates it over a namespaced dictionary `{data, response, turn:{index,total,last}}` (serialized **preserving explicit nulls**, never the shared `NON_NULL` mapper). Invalid JSONata → hard 400 at write time. At run time (only on SUCCESS rows): `true` → run; `false` → omit the metric; throws/non-boolean/null → `ConditionError` (no metric value + a `metricError::<name>` export column) while the row **stays SUCCESS**. See [conditional-metric-execution spec](openspec/specs/conditional-metric-execution/spec.md).

## Key Packages Reference

Full package-by-package map of all three modules (main app, `evaluation-runner-core`, `eval-cli`): **[docs/key-packages.md](docs/key-packages.md)**. Read it when navigating unfamiliar code or deciding where new code belongs.

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
- Pointers to detailed docs and specs

**Deliberately NOT inline** (this file is always loaded — keep these lazy):
- The package-by-package map lives in [docs/key-packages.md](docs/key-packages.md), linked from the Key Packages Reference section. Add new packages there, not here.
- A convention longer than ~2 lines is not an "inline convention" — give it a [docs/patterns/](docs/patterns/README.md) doc plus a one-row table entry. If the "Inline conventions" list starts growing multi-paragraph bullets, extract them.

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

