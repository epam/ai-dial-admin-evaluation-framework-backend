## Why

`docs/configuration.md` is the primary operator-facing reference for deploying and tuning this service, but its format diverges from peer DIAL Admin services (`ai-dial-admin-backend`, `ai-dial-admin-deployment-manager-backend`). The peer docs use a uniform 6-column schema (`Property | Environment Variable | Default | Required | Applied when | Description`) with top-level category grouping. Our doc uses a 4-column table, scatters environment variables inside prose, has no explicit `Required` or `Applied when` columns, and presents 18 flat sections. This makes it harder to scan, harder to diff against peer services during DIAL platform reviews, and harder for operators to know at a glance whether a property is mandatory or conditional. Unifying the structure now — before more properties are added — locks in a format that future contributors must follow.

## What Changes

- **Rewrite `docs/configuration.md`** so every property table uses the 6-column schema: `Property | Environment Variable | Default | Required | Applied when | Description`. Every row must fill every column (use `-` for unconditional `Applied when`).
- **Introduce a fixed Required vocabulary**: `Yes` (must be set, no sensible default), `No` (safe at default everywhere), `Conditional` (depends on another property — condition spelled out in `Applied when`), `Recommended` (has a default but the default is not suitable for production).
- **Add two-level grouping** with 9 top-level groups in a fixed order:
  1. Overview
  2. Spring Framework Configuration
  3. Security
  4. Data Layer
  5. DIAL Integration
  6. Evaluation Engine
  7. Data Management
  8. Observability
  9. Notes
- **Add a new Overview section** covering Spring config precedence (env → YAML → defaults), the fail-fast validation guarantee, and the uppercase-dot-to-underscore env-var conversion rule — surfaced once up front instead of at the bottom.
- **Migrate non-configuration narrative** out of `configuration.md`. Architectural prose (e.g., "lazy bucket discovery", "TransactionTimestampAspect details", "CursorCodec encoding choice") moves to `docs/design/` or is deleted; `configuration.md` becomes a pure operator reference. Behavioural notes that directly explain a config property (e.g., Azure AD flow, Grafana-enabled response fields, metric-provider env-var override pattern) stay — but always AFTER the table in their section, never interleaved.
- **Add an AGENTS.md rule** (`When adding a configuration property`) requiring: every new property must have a row with all 6 columns; `docs/configuration.md` must be updated in the same PR that introduces the property; `Required` value must come from the 4-term vocabulary.
- **Preserve every existing property** — no property key is renamed, added, dropped, or re-defaulted. No `application.yml` changes.

## Capabilities

### New Capabilities
- `configuration-docs`: Governs the structure, schema, and maintenance rules for `docs/configuration.md` — column schema, Required vocabulary, top-level grouping, and the rule that every config property has a documented row.

### Modified Capabilities
_(none — the change rewrites a documentation file and adds one convention rule to AGENTS.md; no existing spec's requirements change.)_

## Impact

- **Docs**: `docs/configuration.md` is rewritten end-to-end. `AGENTS.md` gains one new bullet under the `DO ✅` list.
- **Design docs**: Non-configuration narrative migrates into `docs/design/` (target: `docs/design/infrastructure-architecture.md` or new dedicated files), or is removed where already covered there.
- **Code**: None — no `application.yml`, property class, Java source, migration, or test file changes.
- **APIs**: None.
- **Operators**: Existing env vars and defaults continue to work unchanged. The only visible change is the shape of the reference doc. Operators who grep for env-var names in the doc benefit from the new fully-filled `Environment Variable` column.
- **Future contributors**: New config property PRs are now required (via AGENTS.md) to update `docs/configuration.md` with a complete 6-column row and a vocabulary-compliant `Required` value. OpenSpec spec `configuration-docs` backs the rule so future drift is a detectable spec violation.
- **Rollout**: Single PR. No migration, no feature flag, no deprecation window.
- **Test plan**: No automated tests — docs-only change. Verification is manual review: every existing property present, every table is 6 columns, every `Required` value is from the 4-term vocabulary, every `Applied when` either spells out a condition or is `-`, AGENTS.md rule reads cleanly, design docs pick up migrated narrative without duplication.
