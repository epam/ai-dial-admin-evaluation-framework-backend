## Context

`docs/configuration.md` today uses a 4-column table (`Property | Description | Default`), sprinkles environment variable names inside descriptions as parenthetical `(env: …)` hints, has no explicit `Required` or `Applied when` columns, and lists 18 flat sections. It mixes operator-facing property reference with architectural narrative (e.g., `TransactionTimestampAspect`, lazy bucket discovery, cursor encoding rationale).

Two peer DIAL Admin services — `epam/ai-dial-admin-backend` (`docs/configuration.md`, 439 lines) and `epam/ai-dial-admin-deployment-manager-backend` (`docs/configuration.md`, 735 lines) — converge on a single format:

- 6 columns: `Property | Environment Variable | Default | Required | Applied when | Description`
- Top-level grouping by bounded concern
- Narrative prose relegated to subsections AFTER the table of that section
- One-sentence `Applied when` expression or `-`

The goal of this change is to adopt that format verbatim, with one small refinement: a **fixed 4-term vocabulary for `Required`**. The peer docs use free-form phrases like `"No (recommended to adjust for target environment)"` and `"Yes, if X is specified"`. That's informative but inconsistent — we'll codify the same semantics into four terms: `Yes`, `No`, `Conditional`, `Recommended`.

The AGENTS.md-level rule "when adding a config property, document it this way" is added in the same PR to prevent drift.

Stakeholders: operators deploying the service (primary audience of configuration.md), future contributors adding config properties (must follow the rule), peer DIAL Admin service reviewers (benefit from format parity).

## Goals / Non-Goals

**Goals:**

- Rewrite `docs/configuration.md` so every property table is exactly 6 columns with every cell filled.
- Standardize `Required` to one of `{Yes, No, Conditional, Recommended}`.
- Introduce 9 top-level groups in a fixed order: Overview, Spring Framework Configuration, Security, Data Layer, DIAL Integration, Evaluation Engine, Data Management, Observability, Notes.
- Add a single `When adding a configuration property` rule to AGENTS.md's `DO ✅` list, backed by a new spec `configuration-docs` so the rule is enforceable across future PRs.
- Preserve every existing property, default, and env-var binding. No renames, no removals, no default changes, no `application.yml` edits.
- Migrate non-configuration architectural narrative out of `configuration.md` into `docs/design/` (target: `infrastructure-architecture.md`) or delete it if already covered there.
- Keep the doc's overall byte-size roughly stable (within ±25% of current ~530 lines) — the 6-column tables add width but eliminate duplicated env-var prose.

**Non-Goals:**

- Renaming any configuration key, env-var binding, or default value.
- Adding or removing properties.
- Touching `application.yml`, Java `@ConfigurationProperties` classes, or any code.
- Touching `openspec/config.yaml`. The new rule lives in AGENTS.md (as the user requested) because AGENTS.md is the "always-loaded" authoritative source for contributor Do's/Don'ts. `config.yaml` stays unchanged — per the Config Maintenance Policy litmus test, a documentation-format rule "follows the rules" of how we build; it is a spec-backed convention, not an architectural inflection.
- Automating drift detection (e.g., a CI check that every `@ConfigurationProperties` field has a matching doc row). Nice to have, but out of scope — human review via the AGENTS.md rule is the current enforcement model.
- Localising or translating the doc.

## Decisions

### D1: Adopt the 6-column schema verbatim from peer services

**Decision:** Every property table in `docs/configuration.md` uses exactly these columns, in this order: `Property | Environment Variable | Default | Required | Applied when | Description`.

**Why:** Strict format parity with peer DIAL Admin services (`ai-dial-admin-backend`, `ai-dial-admin-deployment-manager-backend`) makes cross-service review cheap, lets operators use identical mental scanning patterns, and removes a bikeshed axis for every future property addition. A table with fewer columns would be easier to render but diverges from the platform norm; a table with more columns would invent a new standard.

**Alternatives considered:**

- _5 columns — fold `Applied when` into `Description`._ Rejected: we have genuine conditional properties (Azure AD, MCP, file storage, metric-provider sync). Burying conditions inside prose loses scannability and reintroduces the current doc's main weakness.
- _7 columns — split `Description` into "Short" and "Long"._ Rejected: neither peer doc does this, and it doubles the maintenance burden.

### D2: Require every cell to be filled (use `-` for unconditional)

**Decision:** Every row must have a non-empty value in every cell. Use `-` in `Applied when` when the property is unconditional. Use `-` in `Environment Variable` **only** when the env var is the trivial uppercase-dot-to-underscore conversion AND the doc author has deliberately chosen not to call it out (rare; prefer to always spell it out).

**Why:** A half-filled table is worse than no table — readers cannot tell whether a blank cell means "no condition applies" or "author forgot". A uniform sentinel (`-`) removes that ambiguity.

**Alternatives considered:**

- _Leave `Environment Variable` blank when it's the trivial conversion._ Rejected: breaks grep-ability. Operators searching the doc for `DIAL_CORE_URL` or `POSTGRES_META_DATASOURCE_URL` should find the row directly.

### D3: Fixed 4-term `Required` vocabulary

**Decision:** `Required` must be one of exactly four values, each with a precise meaning:

| Term | Meaning | Example |
|---|---|---|
| `Yes` | Must be set; no sensible default. App fails fast at startup without it. | `dial.api-key` |
| `No` | Safe to leave at default in any environment. | `pagination.default-size` |
| `Conditional` | Required only when another property is set. `Applied when` column spells out the condition. | `postgres.meta.datasource.username` when `datasource.meta.auth.type=azure` |
| `Recommended` | Has a default, but the default is not suitable for production. Operators should override. | `postgres.meta.datasource.password` (default `postgres`) |

**Why:** The peer docs use creative free-text ("No (recommended to adjust for target environment)", "Yes, if X is specified") — informative but inconsistent and hard to grep. A small closed vocabulary lets tooling and reviewers verify conformance with a single regex, and lets operators build a mental filter ("show me all `Yes` and `Recommended` for prod checklist").

**Alternatives considered:**

- _3 terms (drop `Recommended`)._ Rejected: loses the "yes the app boots but you're holding a loaded foot-gun" signal. That signal is exactly what operators need during a production deploy.
- _5 terms (add `Deprecated`)._ Rejected: we have no currently deprecated config. Can be added later if/when needed.
- _Copy the peer docs' free-text verbatim._ Rejected: inconsistency is the problem we're fixing.

### D4: Two-level grouping with a fixed, ordered list of 9 top-level groups

**Decision:** Introduce exactly these 9 top-level groups, in this order:

1. **Overview** — Spring config precedence, fail-fast validation statement, env-var conversion rule.
2. **Spring Framework Configuration** — Server, Actuator, OpenAPI, Logging.
3. **Security** — Mode, Identity Providers, JWT Claim Resolution.
4. **Data Layer** — Meta datasource, Analytics datasource, Azure AD, Flyway, Datasource Validation.
5. **DIAL Integration** — Core client, API key, File Storage, MCP client.
6. **Evaluation Engine** — Test Suite Run (executor/SSE/execution/retry/limits/run-config), Analytics Results Batch Write, Analytics Eval Summaries Batch Write, Metric Providers, Metric Evaluation.
7. **Data Management** — Pagination, CSV Export, CSV Import, Validation, Test Case Batch.
8. **Observability** — Grafana Integration. (Logging lives under Spring Framework because it's a Spring facility — operators expect it there.)
9. **Notes** — Docker example, security reminders, deployment cautions.

**Why:** 18 flat sections was too much for a single Table of Contents and had no conceptual anchor — Pagination, Flyway, and Grafana sat at the same depth. Grouping by bounded concern gives the reader a two-step scan path: pick a group, then a section. 9 groups is small enough to hold in working memory. The ordering flows from platform fundamentals (Spring, Security) to app-specific concerns (Evaluation Engine) to ops (Notes).

**Alternatives considered:**

- _Flat 18 sections (status quo shape)._ Rejected: ToC becomes noisy; peer `ai-dial-admin-backend` doc is also flat but shorter (fewer sections).
- _Group by lifecycle (Boot, Runtime, Shutdown)._ Rejected: doesn't match how operators think about config.
- _Group by persona (Developer, Operator, Auditor)._ Rejected: most properties are relevant to multiple personas.

### D5: AGENTS.md carries the rule, not `openspec/config.yaml`

**Decision:** The new "When adding a configuration property" rule lives as a bullet in AGENTS.md's `DO ✅` section. It is backed by a new spec `configuration-docs` (so violations are detectable as spec drift), but `openspec/config.yaml` is NOT updated.

**Why:** Per the Config Maintenance Policy litmus test, a documentation-format rule "follows the rules" rather than "changes the rules" — it's a cross-cutting convention, yes, but it's a convention about docs, not about architecture or code. AGENTS.md is the always-loaded source for contributor Do's/Don'ts and is where the rule will actually be seen by contributors (human and agent). The spec `configuration-docs` gives it a permanent home that future changes must respect.

**Alternatives considered:**

- _Put the rule in `openspec/config.yaml` under a new section._ Rejected: `config.yaml` is for tech stack, architecture, anti-patterns — not doc-format rules. Bloating it dilutes the signal.
- _Put the rule only in the spec, not AGENTS.md._ Rejected: specs are not always loaded into agent context; AGENTS.md is. A rule that isn't in AGENTS.md is a rule that gets forgotten.
- _Put the rule in both config.yaml AND AGENTS.md._ Rejected: duplication invites drift.

### D6: Migrate non-configuration narrative out of `configuration.md`

**Decision:** Prose that explains a component's internal architecture (e.g., "Lazy bucket discovery via `GET /v1/bucket`. Cached in `AtomicReference`", "`TransactionTimestampAspect` initializes timestamp via AOP `@Before`", "Cursor-based pagination avoids OFFSET/LIMIT") is removed from `configuration.md` and either:

- **(a)** moved to `docs/design/infrastructure-architecture.md` (or another `docs/design/` file) if not already covered there, or
- **(b)** simply deleted if already covered in `docs/design/` or `AGENTS.md`.

Prose that directly explains a config property's effect on runtime behaviour (e.g., "Azure AD token refresh", "Grafana-enabled response fields", "Metric Providers env-var override pattern") stays — but as a prose subsection AFTER the relevant table, never interleaved.

**Why:** `configuration.md` is an operator reference. An operator reading it should see "what can I set" answered in the table and "what happens when I set it" answered in prose — never "here's how the component is implemented internally". Internal architecture belongs in `docs/design/`.

**Alternatives considered:**

- _Keep all current narrative for completeness._ Rejected: duplication with `docs/design/` and `AGENTS.md` invites drift.
- _Move all narrative out, tables only._ Rejected: operator-facing behavioural notes (Azure flow, Grafana-enabled fields) are necessary context for the properties themselves and belong alongside the table.

### D7: No automated conformance check

**Decision:** Conformance with the 6-column schema and 4-term `Required` vocabulary is enforced by human review (pointed at the AGENTS.md rule and the `configuration-docs` spec), not by a CI check.

**Why:** Writing a markdown-table linter that understands the 4-term vocabulary is a real project with real maintenance cost. At current property count (~60) and rate of change (a handful per change), human review is adequate. If drift becomes a pattern, a linter can be added later.

## Risks / Trade-offs

- **Risk:** Reviewer fatigue — a 500-line docs diff is hard to review hunk-by-hunk. **Mitigation:** Split the doc rewrite into visible commits per top-level group (one commit per group), with the AGENTS.md change in its own final commit. Reviewers can read each commit independently.

- **Risk:** Information loss during the rewrite — a property might be dropped accidentally. **Mitigation:** Tasks include an explicit verification step: dump the current property list, dump the new property list, diff them — difference must be empty. `grep -oE '\b[a-z][a-z0-9.-]+\.[a-z][a-z0-9.-]+\b' docs/configuration.md | sort -u` as a crude extraction baseline; confirmation via visual side-by-side for env-var column completeness.

- **Risk:** The `Required` vocabulary applied wrong at migration time — e.g., `dial.api-key` marked `No` because the property binding has a default of empty string. **Mitigation:** The vocabulary is tied to *operational* necessity, not whether Spring binds a default. If the app refuses to function without the value, it's `Yes`. Reviewer sanity-checks each `Yes` and `Recommended` by asking "what happens if this is missing?". Cross-check with `@NotBlank` / `@NotNull` annotations in the properties classes.

- **Risk:** `Applied when` expressions drift in syntax across tables. **Mitigation:** The spec `configuration-docs` (added in this change) codifies the expression format: `<property-path>=<value>` or `<property-path>!=<value>`, or `<condition-A> AND <condition-B>`. Free-form English is disallowed.

- **Risk:** Narrative migrated to `docs/design/infrastructure-architecture.md` duplicates content already there. **Mitigation:** Before each migration, search the target file for overlap; prefer to delete the configuration.md version if design coverage already exists.

- **Trade-off:** 6-column tables render taller on GitHub than 4-column tables, especially on narrow viewports. Accepted — scannability of a wide, uniform table beats the visual cost of extra wrapping.

- **Trade-off:** The 4-term vocabulary is less expressive than free-text. Accepted — consistency and greppability are worth the small loss of nuance; edge cases (e.g., "required unless you also set Y and Z") can be explained in `Applied when` or in the post-table prose subsection.

## Migration Plan

Single PR, docs-only. No runtime migration, no feature flag, no deprecation window.

1. Add the new spec `configuration-docs` describing the 6-column schema, 4-term vocabulary, top-level grouping, and the "every property has a row" rule.
2. Rewrite `docs/configuration.md` following the new structure. Preserve every existing property.
3. Add the `When adding a configuration property` bullet to AGENTS.md's `DO ✅` list, pointing at the `configuration-docs` spec.
4. Migrate non-configuration narrative to `docs/design/infrastructure-architecture.md` or delete where duplicated. (Smaller migrations can ride in this same PR; larger ones can spin off into follow-up changes if they'd bloat the diff.)

Rollback: revert the PR. Since no code or config semantics change, rollback is risk-free.

## Resolved Questions

- **Overview → spec link.** `docs/configuration.md`'s Overview SHALL include one line linking to `openspec/specs/configuration-docs/spec.md`, so readers wondering "why is this doc shaped this way?" can find the governance doc. One line, zero ongoing cost.
- **AGENTS.md rule verbosity.** The AGENTS.md bullet SHALL reference the `configuration-docs` spec rather than enumerating the 9 top-level groups or the 4-term `Required` vocabulary inline. Keeps AGENTS.md terse and prevents the group list from drifting across two sources of truth.
