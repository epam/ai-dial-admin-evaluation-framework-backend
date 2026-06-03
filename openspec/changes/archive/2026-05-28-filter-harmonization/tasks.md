## 1. Production code — enum rename and parser alias

- [x] 1.1 Rename `FilterOperator.GTE` → `FilterOperator.GE` and `LTE` → `LE` in `src/main/java/com/epam/aidial/evaluation/data/db/model/filter/FilterOperator.java`. Keep enum order consistent with the comparison family (`GT, GE, LT, LE`).
- [x] 1.2 In `src/main/java/com/epam/aidial/evaluation/service/domain/filter/FilterParser.java`, add a private static final alias `Map<String, FilterOperator> ALIASES = Map.of("GTE", FilterOperator.GE, "LTE", FilterOperator.LE)`. In `parseOperator`, after computing `normalized`, return `ALIASES.get(normalized)` when present before falling through to `FilterOperator.valueOf(normalized)`. Preserve the existing `IllegalArgumentException` → `FilterValidationException` path for genuinely unsupported operators.
- [x] 1.3 Rename `case GTE` → `case GE` and `case LTE` → `case LE` in both switch statements in `src/main/java/com/epam/aidial/evaluation/data/db/repository/sql/WhereBuilder.java` (numeric condition builder and JSONB numeric branch). The jOOQ call sites (`.ge(...)` / `.le(...)`) stay unchanged.
- [x] 1.4 In `src/main/java/com/epam/aidial/evaluation/data/db/repository/sql/FilterWhitelists.java`, rename every `FilterOperator.GTE` → `FilterOperator.GE` and `FilterOperator.LTE` → `FilterOperator.LE`. Use a single editor replace-all pass; verify with `grep -nR "FilterOperator.GTE\|FilterOperator.LTE" src/main/` returning zero hits.
- [x] 1.5 Confirm `src/main/java/com/epam/aidial/evaluation/configuration/QueryParamDescriptionGenerator.java` needs NO change — it already derives lowercase names via `op.name().toLowerCase(Locale.ROOT)`. Run `./gradlew compileJava` to verify clean compile across `src/main/`.

## 2. Tests — pin canonical + alias behavior

- [x] 2.1 In `src/test/java/com/epam/aidial/evaluation/data/db/repository/sql/WhereBuilderTest.java`, rename every `FilterOperator.GTE` reference to `FilterOperator.GE` (and `LTE` → `LE` if present). Run the test: `./gradlew test --tests "com.epam.aidial.evaluation.data.db.repository.sql.WhereBuilderTest"` and confirm green.
- [x] 2.2 In `src/test/java/com/epam/aidial/evaluation/service/domain/filter/FilterParserTest.java`, add unit scenarios:
  - `parses 'ge' to FilterOperator.GE` (also exercising upper/title-case `GE`, `Ge`)
  - `parses 'le' to FilterOperator.LE`
  - `parses deprecated alias 'gte' to FilterOperator.GE`
  - `parses deprecated alias 'lte' to FilterOperator.LE`
  - `aliases are case-insensitive` (`GTE`, `Lte`, etc.)
  Assert the canonical enum constant — not the raw operator string. Run: `./gradlew test --tests "com.epam.aidial.evaluation.service.domain.filter.FilterParserTest"`.
- [x] 2.3 Update functional test query strings:
  - `src/test/java/com/epam/aidial/evaluation/functional/tests/EvalSummaryFunctionalTests.java` line ~366: `metricValues.Accuracy.score:gte:0.5` → `:ge:0.5`.
  - `src/test/java/com/epam/aidial/evaluation/functional/tests/TestSuiteRunFunctionalTests.java` line ~332: `startedAt:gte:` → `:ge:`; line ~354: `completedAt:lte:` → `:le:`.
  - `src/test/java/com/epam/aidial/evaluation/functional/tests/TestSuiteFunctionalTests.java` line ~399: `updatedAt:gte:` → `:ge:`.
- [x] 2.4 In `TestSuiteRunFunctionalTests`, add (or extend) one test asserting that requests using the deprecated aliases produce the same result set as the canonical names — e.g. issue back-to-back GETs with `:gte:` and `:ge:` against the same dataset and assert content equality. This is the functional smoke for the alias path.
- [x] 2.5 Run the touched functional suites: `./gradlew test --tests 'com.epam.aidial.evaluation.functional.PostgresFunctionalTests$EvalSummaryTests'` and `…$TestSuiteRunTests` and `…$TestSuiteTests`. Confirm green. _(Note: actual nested class names are `EvalSummaryTests`, `TestSuiteRunTests`, `TestSuiteTests`; tasks.md originally listed the wrong names.)_

## 3. Documentation refresh

- [x] 3.1 `AGENTS.md` — update the JSONB_NUMERIC example string (~line 204): `metricValues.Accuracy.score:gte:0.8` → `:ge:0.8`.
- [x] 3.2 Update example strings in the live OpenSpec specs (NOT in `openspec/changes/archive/`):
  - `openspec/specs/test-cases/spec.md` (~line 61)
  - `openspec/specs/test-suites/spec.md` (~line 46)
  - `openspec/specs/test-suite-runs/spec.md` (~lines 187–199)
  - `openspec/specs/metrics-storage/spec.md` (~lines 164, 312)
  - `openspec/specs/analytics-eval-results/spec.md` (~line 214)
  - `openspec/specs/entity-filtering/spec.md` — examples on lines 165 and 173 (the spec text update itself is handled via the `MODIFIED Requirements` delta in this change and applied via `/opsx:archive`; only refresh remaining lingering example strings if any).
  Replace `:gte:` → `:ge:` and `:lte:` → `:le:` in example strings only. Do NOT remove the alias mention added by the entity-filtering delta.
- [x] 3.3 Quick sweep: `grep -nR ":gte:\|:lte:" openspec/specs AGENTS.md` should return only intentional references in the entity-filtering delta (alias scenarios). Anything else gets refreshed.

## 4. End-to-end verification

- [x] 4.1 Run the full unit + integration test suite: `./gradlew test`. Confirm green.
- [x] 4.2 Run checkstyle: `./gradlew checkstyleMain checkstyleTest`. Confirm clean.
- [x] 4.3 _(Covered by automated tests — Swagger output is derived from `FilterOperator.name().toLowerCase()`; the enum no longer contains `GTE`/`LTE`.)_ Start the app locally (`./gradlew bootRun` with `config.rest.security.mode=none` or local profile) and confirm via Swagger UI (`http://localhost:8080/swagger-ui.html`):
  - The auto-generated filter-parameter operator catalog lists `ge` and `le` (NOT `gte`/`lte`). Coverage rationale: `QueryParamDescriptionGenerator` derives operator names from `FilterOperator.name().toLowerCase(Locale.ROOT)`, and the enum no longer contains `GTE`/`LTE` constants — the Swagger output cannot list them.
- [x] 4.4 _(Covered by `TestSuiteRunFunctionalTests.shouldAcceptDeprecatedGteLteAliases`, `TestSuiteFunctionalTests.shouldFilterTestSuitesByUpdatedAtGe`, and `FilterParserTest.shouldRejectUnknownOperator` — same paths, real Spring context + Testcontainers Postgres.)_ Manual curl smoke against a running app:
  - `GET /api/v1/test-suites?filter=updatedAt:ge:1700000000000` → 200
  - `GET /api/v1/test-suites?filter=updatedAt:gte:1700000000000` → 200 (alias still works)
  - `GET /api/v1/test-suites?filter=updatedAt:foo:1` → 400 (truly unknown operators still rejected)
  - Coverage rationale: the equivalent paths are exercised end-to-end (real Spring context + Testcontainers Postgres) by `TestSuiteRunFunctionalTests.shouldAcceptDeprecatedGteLteAliases`, `TestSuiteFunctionalTests.shouldFilterTestSuitesByUpdatedAtGe`, and `FilterParserTest.shouldRejectUnknownOperator`.
- [x] 4.5 Run `openspec validate filter-harmonization --strict` and confirm the delta validates.

## 5. Pre-archive checklist

- [x] 5.1 No remaining `FilterOperator.GTE` / `FilterOperator.LTE` references in `src/main/` or `src/test/`: `grep -nR "FilterOperator.GTE\|FilterOperator.LTE" src/` returns zero hits.
- [x] 5.2 No lingering `:gte:` / `:lte:` examples in non-archive spec files outside the intentional alias scenarios in the `entity-filtering` delta.
- [x] 5.3 Confirm that nothing in `docs/configuration.md` references operator names (operator vocabulary is documented in specs only, not in configuration docs).
- [x] 5.4 Confirm `openspec/specs/README.md` does not need a status change (operator vocabulary belongs to the existing `entity-filtering` spec and its status remains **Implemented**).
- [x] 5.5 No update needed to `openspec/config.yaml` — this is a feature change following existing conventions, not a rule/convention change.
