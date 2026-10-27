## Why

DIAL Core now exposes **skills** — directory-shaped resources addressed as `skills/{bucket}/{path-to-skill}` — and evaluation suites need to pass a reference to an existing skill into a deployment/tool request. Today the only DIAL-resource reference type is `FILE`, which is wrong for a skill: a `FILE` value is resolved to a DIAL file ref (`files/…`), may be downloaded for multipart parts, is packed into ZIP exports and is subject to `@ef`/`public` ownership checks, none of which apply to a directory resource. Authors currently have to model a skill reference as a plain `STRING` with no validation and no typed hint (GH #268, frontend counterpart ai-dial-admin-frontend#5082).

## What Changes

- **New schema type `SKILL`** on `SchemaFieldType` (runner-core, shared enum), valid wherever `FILE` is a schema type today: test-suite / dataset test-case schema fields, response columns. A `SKILL` value is a string-backed DIAL resource reference. Type-agnostic code treats it like `STRING` (JSON-schema `"string"`, query `STRING`, CSV cell verbatim, coercion).
- **New request-template type hint `|skill`**, case-insensitive (`${{selectedSkill|skill}}`, `|SKILL`), in every body form that supports `|file`: structured JSON, URL-encoded and JSONata bodies, and MCP tool arguments. A **full-value** `|skill` placeholder (explicit hint, or a hintless placeholder bound to a `SKILL` column) resolves to the canonical reference `skills/{bucket}/{path-to-skill}`; embedded (partial-string) placeholders stay verbatim, mirroring `FILE`.
- **Validation**: a non-empty `SKILL` test-case value, and a constant binding to a `|skill` variable, that is not a well-formed skill reference produces a validation **warning** via the existing validation-warning model (`TYPE` warning `SKILL field '<name>': …`). No existence / access / ownership checks — DIAL Core stays authoritative for existence, access rights and request-time sharing.
- **Preservation**: `SKILL` values and the `SKILL` column type round-trip unchanged through API, persistence (JSONB), CSV import/export, ZIP manifest, suite snapshot, suite/dataset clone, and try-it-out template-variable discovery (`declaredType`/`effectiveType`).
- Schema-change revalidation: `SKILL` ↔ `STRING` follows the same strict String-identity rule as `FILE` in `SchemaChangeCoercer`.
- OpenAPI descriptions/examples updated for the new enum value and `|skill` hint.

Explicitly **not** changing:
- No skill creation, upload, copy, deletion or lifecycle management; no calls to DIAL Core skill routes (files inside a skill stay under Core's separate skill-file routes).
- A skill is **not** a multipart file part and is never downloaded or materialised as binary: `FormPartType`, `MultipartFormDataRequestBodySerializer`, `DialFileRefResolver.resolveToRealPath` and the FILE-to-DIAL-ref path are untouched.
- `SKILL` columns never force a ZIP export (`ZipExportService.hasFileFields`), are never rewritten on ZIP import, and are never copied by suite/dataset clone (nothing to copy).
- No existence/ACL/ownership validation; no `@ef`/`public` alias semantics for skills.
- No DB schema changes and no Flyway migration (field type lives inside JSONB; no `CHECK` constraint exists on it). No configuration properties, so `docs/configuration.md` is unchanged.

## Capabilities

### New Capabilities

- `dial-skill-ref`: canonical skill-reference format `skills/{bucket}/{path}`, its well-formedness rules, normalisation of a stored value to the canonical form for request payloads, and the guarantee that a skill reference is never downloaded or materialised.

### Modified Capabilities

- `request-template`: `|skill` type hint (case-insensitive) in JSON, URL-encoded and JSONata bodies; full-value placeholder resolves to the canonical ref, embedded stays verbatim; `declaredType`/`effectiveType` may be `SKILL`; constant binding to a `|skill` variable is format-checked.
- `test-cases`: `SKILL` row in the field-type table; malformed non-empty value → `TYPE` validation warning; CSV/permissive coercion and strict schema-change coercion rules; CSV export stays CSV (no ZIP) for `SKILL` columns.
- `datasets`: `SKILL` is an allowed dataset schema field type.
- `response-columns`: `SKILL` is an allowed column type, behaving like `STRING`.
- `mcp-tool-invocation`: `|skill` placeholder in MCP tool arguments resolves to the canonical ref.
- `test-case-zip-archive`: `SKILL` columns are not packed, not rewritten, and keep their type through the manifest round trip.
- `file-ref-validation`: `SKILL` values/constants are outside file-ownership validation; skill-ref format validation is separate.
- `try-it-out`: variable discovery and resolved-request/try-out show the canonical skill ref for `|skill` variables.

## Impact

- **Code — `evaluation-runner-core`**: `dto/SchemaFieldType` (+`SKILL`); new `client/dialcore/SkillRefResolver` (format check + canonicalisation, injectable, picked up by `EvaluationRunnerAutoConfiguration`'s component scan); skill-placeholder detection beside `FilePlaceholderDetector` (FILE logic not modified); `TemplateContentResolver`, `JsonataSourcePreprocessor`, `McpRequestResolver` (full-value skill resolution); `ResponseColumnTypeReconciler` (`SKILL` case).
- **Code — root app**: `TestCaseValidationService` (`isTypeCompatible`, new `validateSkillFields`), `BindingValidator` (skill constant check), `SchemaTypeCoercer`, `SchemaChangeCoercer`, `SchemaValidationService.jsonSchemaType`, `query/service/SchemaFieldTypeMapper`; `TryItOutVariableBindings.bindToFileColumns` (also carries a `SKILL` column as `dataField`, design D7); `TemplateVariableDto` OpenAPI text/example; controller OpenAPI text. Verify (no change expected): `TemplateVariableExtractor` hint parsing, `TemplateVariableService`, CSV import/ZIP resolvers, manifest serializer.
- **API**: additive only — new enum value `SKILL` in field/response-column `type` and `declaredType`/`effectiveType`; new `|skill` hint. Existing clients unaffected; clients that exhaustively switch on the type enum (frontend, eval-cli consumers) must accept `SKILL`.
- **Security**: no new endpoints or auth changes; no outbound calls added. The skill ref is passed through as a string and DIAL Core enforces access. Format check rejects `..`/empty segments/control characters to prevent path-shaped surprises in payloads.
- **Rollout / risk**: no migration ordering concerns; ships as a regular release (milestone release-0.23). Main risk is missing an exhaustive `switch (SchemaFieldType)` — mitigated by compile errors (no `default` branches) and unit tests per touched switch. Reading `SKILL` from a newer DB on an older build is not supported (enum deserialisation), i.e. no rollback after skill columns are saved.
- **Tests**: unit (new `SkillRefResolverTest`; extended detector/resolver/validation/coercer/mapper/extractor/manifest tests) + functional (`SkillFieldFunctionalTests` registered in `PostgresFunctionalTests`: schema + test case warning, CSV round trip, ZIP export stays CSV, clone/snapshot preservation, template variables, try-out resolved ref); `SkillUsageExpressionsTest` (runner-core, pins the documented `skill_used`/`skill_stage` response-column expressions), a nested `custom_content.skills[{url}]` case in `TemplateContentResolverTest`, and a `/v3/api-docs` assertion for the suite `skill` example.
- **Examples/Docs (skill attachment shape)**: DIAL Core takes skills as `custom_content.skills[{"url": …}]`; the suite examples `api-v1-test-suites-POST-request-skill.json` / `…-response-201-skill.json` show it and `docs/patterns/jsonata-evaluation-seam.md` documents the shape plus the `skill_used`/`skill_stage` columns (no framework code change, design D9).
- **Docs**: `docs/database-schema.md` (type enum list), `docs/patterns/jsonata-evaluation-seam.md`, `docs/patterns/dial-file-storage.md`, `docs/patterns/schema-coercers.md`; `openspec/specs/README.md` (new `dial-skill-ref` row) at archive. `AGENTS.md`/`config.yaml` unlikely to need updates (follows existing patterns).
