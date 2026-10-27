# Design — `SKILL` schema type and `|skill` request-template hint

## Context

See `proposal.md` — Why. Current state that shapes the approach:

- `SchemaFieldType` (runner-core `dto`) is `STRING, INTEGER, NUMBER, BOOLEAN, OBJECT, ARRAY, FILE`. The type is stored inside JSONB (`testCaseSchema`, response columns, suite snapshot); there is no enum column or `CHECK` on it, so a new value needs no Flyway migration.
- `TemplateVariableExtractor.parseTypeHint` does `SchemaFieldType.valueOf(upper)`; a new enum constant therefore makes `${{x|skill}}` parse (case-insensitive) with no extra code.
- `FilePlaceholderDetector.isFileReference(typeHint, binding, schema)` is the single decision point for "is this full-value placeholder a FILE ref" (explicit hint wins; otherwise the bound `dataField`'s schema type). Its result is consumed by **three copies of the same `if`**: `TemplateContentResolver.resolveObject` (JSON/URL-encoded), `JsonataSourcePreprocessor.resolveTypedValue` (JSONata) and `McpRequestResolver.resolveFileRef` (MCP). Each then calls `DialFileRefResolver.resolveToDialRef`.
- `FormPartType.FILE` (multipart part) is a different enum; `MultipartFormDataRequestBodySerializer` downloads bytes via `DialFileRefResolver.resolveToRealPath` + `DialFileClient.download`. This is the path a skill must never reach.
- Exhaustive `switch (SchemaFieldType)` statements without `default` will stop compiling when `SKILL` is added: `ResponseColumnTypeReconciler`, `TestCaseValidationService.isTypeCompatible`, `SchemaTypeCoercer`, `SchemaChangeCoercer`, `SchemaValidationService.jsonSchemaType`, `SchemaFieldTypeMapper`. That is a feature: the compiler lists every place to decide.
- FILE-only behaviours that are already type-gated and so correctly ignore `SKILL` with no code change: `ZipExportService.hasFileFields`, `ZipExportFileCollector`, `ZipImportColumnTypeResolver.eligibleForRewrite()` (`type == null || type == FILE`), `ZipCsvFileRefRewriter`, `TryItOutVariableBindings.bindToFileColumns`, clone `copyFilesBetween*` (folder based).
- DIAL Core's skill model: a skill is a directory resource addressed `skills/{bucket}/{path}`; its files are served by separate Core skill-file routes; Core owns existence, ACLs and request-time sharing.

## Goals / Non-Goals

**Goals:**
- `SKILL` is a first-class, string-backed schema type everywhere `FILE` is a schema type, preserved through API, JSONB, CSV, ZIP manifest, snapshot, clone and try-out.
- `|skill` (any case) resolves a full-value placeholder to the canonical `skills/{bucket}/{path}` in JSON, URL-encoded, JSONata and MCP argument paths, with one shared decision point so the paths cannot drift.
- Malformed non-empty values surface through the existing validation-warning model, before a run.
- FILE behaviour is byte-for-byte unchanged.

**Non-Goals:**
- Skill creation/upload/copy/delete/lifecycle; calling Core skill routes; existence or ACL checks; `@ef`/`public` alias semantics for skills.
- Treating a skill as a multipart part, downloading it, packing it into ZIP, or copying it on clone.
- Any DB migration or configuration property.
- Generalising `FILE` and `SKILL` into a "resource reference" type hierarchy (see D1 alternative).

## Decisions

### D1 — Separate enum constant `SKILL`, string-backed; not a flag on `FILE`
Add `SKILL` to `SchemaFieldType`. Wherever code is type-agnostic it groups `SKILL` with `STRING` (`case STRING, SKILL ->`), and `FILE` stays its own group where it has FILE-only behaviour. `FormPartType` is untouched.

*Alternative rejected:* reuse `FILE` with a `kind=skill` attribute — `FILE` carries ZIP-packing, download, ownership and `files/` semantics that all would need `kind` guards; a new enum value fails closed (compiler-checked switches, FILE-gated ZIP code ignores it by construction).

### D2 — `SkillRefResolver`: one injectable owner of the skill-ref format
New `@Component @LogExecution` `client/dialcore/SkillRefResolver` in `evaluation-runner-core` (sibling of `DialFileRefResolver`, picked up by `EvaluationRunnerAutoConfiguration`'s `@ComponentScan("com.epam.aidial.evaluation.runner")`, exactly like `DialFileRefResolver`, so no explicit registration is needed; the root app reuses it because root already depends on runner-core — one format definition, no duplication across modules). API:
- `Optional<String> validate(String value)` → empty when well-formed, otherwise a human-readable error (shape mirrors how `FileRefValidator` reports, so `TestCaseValidationService` can wrap it in a `TYPE` warning).
- `String toCanonicalRef(String value)` → canonical form for payloads.

Well-formed = after trimming whitespace and a single trailing `/`: starts with the case-sensitive prefix `skills/`, has a non-empty `{bucket}` segment and at least one non-empty path segment, and no segment is `.`/`..`, contains a backslash, or contains control characters. Canonical = that trimmed value. Segment charset beyond that is **not** restricted (Core is authoritative; over-restricting would reject legitimate Core names).

Not routed through `DialFileRefResolver`: its prefix whitelist and `files/` conversion are file semantics (`resolveToRealPath` would reject or mangle `skills/…`), and sharing it would invite a skill ref into the download path.

*Alternatives rejected:* (a) extend `DialFileRefResolver` — mixes two reference kinds and puts the download-capable methods next to skill values; (b) put the format in the root app's `FileRefValidator` — runtime resolution lives in runner-core (DB-free, also used by eval-cli) and cannot depend on root; duplicating the regex would drift.

### D3 — One placeholder-conversion seam instead of three copied `if`s
New `@Component @LogExecution` `service/ResourceRefPlaceholderResolver` in runner-core with `Object resolve(Object resolved, String typeHint, InputBindingDto binding, List<FieldDefinitionDto> schema)`:
- if `resolved` is not a `String` → returned unchanged;
- `FilePlaceholderDetector.isFileReference(...)` → `DialFileRefResolver.resolveToDialRef` (exactly today's behaviour);
- else new `isSkillReference(...)` (explicit hint `skill`, case-insensitive; or no hint and the bound `dataField`'s schema type is `SKILL`; same rule shape as FILE, implemented as a second method on the existing detector that reuses `boundFieldType`) → `SkillRefResolver.toCanonicalRef`;
- otherwise unchanged.

`TemplateContentResolver`, `JsonataSourcePreprocessor` and `McpRequestResolver` replace their inline FILE `if` with one call. The FILE branch inside the seam is a verbatim move, so existing FILE tests pass unchanged. Embedded (partial-string) placeholders never reach this seam (they take `resolveString`), so they stay verbatim — same as FILE.

A malformed value reaching run time is passed through **unchanged** (canonicalisation of an unparseable value is a no-op) and DIAL Core rejects it; validation warnings (D4) are the author-facing signal. This matches "Core is authoritative" and avoids failing a whole run on a value validation already warned about.

*Alternative rejected:* add a second `if (skill)` next to each of the three FILE `if`s — smallest diff, but triples the chance of drift in the exact place this codebase already centralised on purpose (see `FilePlaceholderDetector` javadoc).

An explicit non-`file`, non-`skill` hint (e.g. `|string`) still wins over a bound column type, as today: `${{v|string}}` bound to a `SKILL` column stays verbatim.

### D4 — Validation (root app): warnings only, format only
- `TestCaseValidationService`: `isTypeCompatible` → `case STRING, SKILL -> value instanceof String`; blank-String-is-empty rule extended to `SKILL` (required checks); new `validateSkillFields` (beside `validateFileFields`) emits a `TYPE` warning `SKILL field '<name>': <error>` per malformed non-empty value, using `SkillRefResolver.validate`. No ownership/existence check and no `FileRefValidator` involvement.
- `BindingValidator`: a constant binding to a variable whose declared type is `SKILL` is format-checked via `SkillRefResolver.validate`; the FILE suite-ownership/dataset-shape rejection stays FILE-only. Constant-binding failures (FILE and SKILL alike) are `TYPE` validation warnings, never a 400, so the two hints behave alike for authors (see Open Questions).
- Validation uses `SkillRefResolver` as an injected collaborator (specialised component per layering rules); no inline regexes in services.

### D5 — Coercion, schema mapping, reconciliation
- `SchemaTypeCoercer` (permissive, CSV): `SKILL` → `coerceToString`, like `STRING`/`FILE`.
- `SchemaChangeCoercer` (strict, revalidation after schema-type change): `SKILL` target uses the same String-identity rule as `FILE` (`coerceToFile` generalised/aliased); non-String values to `SKILL` stay un-coercible. Class javadoc "→ FILE except String identity" extended.
- `SchemaValidationService.jsonSchemaType`: `SKILL` → `"string"`. `SchemaFieldTypeMapper`: `SKILL` → `QueryFieldType.STRING`. `ResponseColumnTypeReconciler`: `case STRING, FILE, SKILL -> reconcileString(...)`.
- `TestCaseValidationService`'s existing type-compat path still runs first; `validateSkillFields` only adds the format warning.

### D6 — Import/export/clone/snapshot need no behavioural change; prove it by tests
- CSV: `CsvImportService` never infers `FILE`/`SKILL` from content (`inferCellType`); a `SKILL` column arrives through the manifest or an existing schema and `coerceToString` keeps the cell verbatim (no archive-path rewriting, because `ZipImportColumnTypeResolver.eligibleForRewrite` is FILE-only).
- Export: `ZipExportService.hasFileFields` stays FILE-only → a suite/dataset with only `SKILL` columns exports plain CSV, never ZIP. `ZipManifestSerializer` writes the enum by name → `SKILL` round-trips; covered by a test rather than code.
- Clone/snapshot: JSONB copy carries the type; clone's file-copy step is folder-based and has nothing for a skill. No change; covered by a functional test.
- `CsvSchemaFieldBuilder.resolveType` is `fileColumns`-hint-driven (FILE-only) → a ZIP-less CSV import cannot produce `SKILL` on its own; that is intentional (no content sniffing — a `skills/…` string in a `STRING` column stays a string until the author retypes it).

### D7 — Try-out / template-variable discovery
`TemplateVariableService.effectiveType` already falls back declared hint → endpoint type → bound schema type; `SKILL` flows through. `TemplateVariableDto.declaredType`'s OpenAPI description/`example` list adds `SKILL`. `TryItOutVariableBindings.bindToFileColumns` is extended to carry a `SKILL` column as well as a `FILE` column (its method name is kept — the main try-it-out spec and docs reference it): variables try-out replaces the suite bindings with constant bindings that have no `dataField`, so without this a hintless variable bound to a SKILL column would lose its column type and stay verbatim, unlike in a real run. A `constantValue`-only binding with no suite binding to a SKILL column still needs the explicit `|skill` hint, exactly as for FILE. (The first draft of D7 kept this method FILE-only; the apply-time sweep found that this contradicts the try-it-out delta spec.)

### D9 — Wire shape is the deployment's contract; the framework is shape-agnostic
DIAL Core requires each `custom_content.skills` item to be an object with a non-blank `url` (a plain string → 400 `Invalid skill attachment`), and only checks/shares the skill — a skill-aware application loads it and reports a `Reading Skill: <name>` stage. The framework needs no knowledge of this: a full-value placeholder is resolved at any depth in JSON/URL-encoded/JSONata bodies, so `{"custom_content": {"skills": [{"url": "${{s|skill}}"}]}}` works unchanged. The group-9 correction (first examples showed a flat `skill` key) was therefore docs/examples/tests only — no framework code change. MCP argument keys are tool-defined, so MCP specs use whatever key the tool declares.

### D8 — Layering and logging conventions
`SkillRefResolver`/`ResourceRefPlaceholderResolver` follow runner-core's existing `@LogExecution` import; neither takes a plaintext secret (Exception 1 N/A). No new packages. `RunnerModuleConstraintsTest`/`LayeredArchitectureTest` stay green: runner-core gains no root dependency; root only injects a runner-core bean, already the pattern for `DialFileRefResolver`.

## Risks / Trade-offs

- [Missed exhaustive switch or `SchemaFieldType` consumer outside the enumerated list] → compiler failure for switches without `default`; repo-wide grep for `SchemaFieldType.FILE`/`FILE ->` during implementation; a unit test per touched switch. Consumers that use `default`/`if` (e.g. `EndpointSchemaExtractor.mapTypeName`) are reviewed individually — it never maps JSON-schema names to `FILE`, so it correctly never yields `SKILL` either.
- [Refactoring the FILE branch into `ResourceRefPlaceholderResolver` regresses FILE] → branch moved verbatim; existing `TemplateContentResolverTest`, `JsonataSourcePreprocessorTest`, `McpRequestResolverTest`, `FilePlaceholderDetectorTest` are run unchanged before adding SKILL cases; `FileFieldFunctionalTests` as the end-to-end guard.
- [Over-strict format rejects a valid Core skill path] → format check limited to structural invariants (prefix, non-empty segments, no `.`/`..`/backslash/control chars); Core stays authoritative and the check is a *warning* for test-case values.
- [Lenient canonicalisation (trim, trailing `/`) hides author typos] → only whitespace and one trailing slash are normalised; anything else malformed is left as-is and warned about.
- [Forward-only data] → a `SKILL` value saved by this build cannot be deserialised by an older build (unknown enum). Standard for this project's enum additions; no rollback after skill columns exist (noted in rollout).
- [Clients with exhaustive type switches (frontend, eval-cli consumers)] → additive API value; frontend change tracked separately (ai-dial-admin-frontend#5082); eval-cli only uses `STRING` in tests and relies on runner-core resolution.
- [Skill ref in a `STRING` column with `|skill` hint] → resolved by the explicit hint (explicit wins), so existing suites can opt in without a schema change.

## Migration Plan

1. Ship runner-core + root together (single repo/release; milestone release-0.23). No Flyway migration, no config, no data backfill.
2. Existing data is unaffected: no existing value has type `SKILL`.
3. Rollback: revert the release **before** any `SKILL` column is saved; afterwards older builds cannot read such suites/datasets (enum deserialisation). If a rollback is required afterwards, retype the `SKILL` columns to `STRING` first (values are preserved as strings).
4. Docs: `docs/database-schema.md` enum list; `docs/patterns/{jsonata-evaluation-seam,dial-file-storage,schema-coercers}.md`; `openspec/specs/README.md` row for `dial-skill-ref` at archive.

## Open Questions

All three questions were resolved while implementing; none remain open.

- **Malformed `|skill` constant binding — hard 400 or warning?** Resolved: a `TYPE` validation **warning** (`Constant binding for '|skill' variable '<name>': <error>`), never a 400. `BindingValidator` reports the FILE constant-binding failures (ownership, format, dataset-shape) as `TYPE` warnings too, so the two hints behave alike (spec: `dial-skill-ref`, "Skill value validation warnings").
- **Tolerate a leading `/` (`/skills/b/p`)?** Resolved: no — it is malformed (warned), not silently stripped; only surrounding whitespace and one trailing `/` are normalised (`SkillRefResolverTest`).
- **Should EF check skill existence against DIAL Core?** Resolved: no — out of scope; DIAL Core stays authoritative for existence, access and sharing.
